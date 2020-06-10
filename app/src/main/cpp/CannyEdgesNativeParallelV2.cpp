//
// Created by josef on 08/06/2020.
//

#include <jni.h>
#include <string>
#include <android/log.h>
#include <pthread.h>
#include <syslog.h>

#include <stdlib.h>
#include <stdio.h>
#include <math.h>
#include <iostream>
#include <ctime>

#include <array>
#include <vector>
#include <thread>
#include <chrono>

#define blackPixel -16777216
#define whitePixel -1

class CannyEdgesNativeParallelV2{

    //Matriz de magnitud de los bordes
    double* matriz_es;	//Matriz de magnitud de los bordes
    int* matriz_direccion;	//Matriz de dirección de los bordes
    double* matriz_nomax;	//Matriz de no máximos
    int* matriz_visitados;	//Matriz de píxeles visitados
    int* matriz_umbral;	//Matriz binaria resultante de la umbralización
    int width;
    int height;
    double* matriz_Jx;	//Matriz convolucionada con máscara X
    double* matriz_Jy;	//Mátriz convolucionada con máscara Y
    int u_max; //Umbral máximo
    int u_min; //Umbral mínimo
    int** mascara_filtro_x;	//Máscara genérica X
    int** mascara_filtro_y;	//Máscara genérica Y
    unsigned char* data;
    int nThreads;
    int* matriz_image; //Imagen a devolver
    int size;

public:
    //CannyEdgesNativeParallel(int width, int height, unsigned char* data, int nThreads){
    void lanzar_hilos(int width, int height, unsigned char* data, int nThreads) {
        this->width = width;
        this->height = height;
        this->data = data;
        //nThreads = 1;
        this->nThreads = nThreads;
        this->u_max = 250*10000; //Umbral máximo
        this->u_min = 200*10000; //Umbral mínimo
        int size=width*height;
        this->size = size;

        //Inicializar matrices
        matriz_es = new double[size];
        matriz_direccion = new int[size];
        matriz_nomax = new double[size];
        matriz_visitados = new int[size];
        matriz_umbral = new int[size];
        matriz_image = new int[size];
        matriz_Jx = new double[size];
        matriz_Jy = new double[size];
        mascara_filtro_x = new int*[3];
        mascara_filtro_y = new int*[3];
        for(int i = 0; i<3;++i) {
            mascara_filtro_x[i] = new int[3];
            mascara_filtro_y[i] = new int[3];
        }

        int k = 2;
        //GradienteX
        mascara_filtro_x[1 - 1][1 - 1] = -1;
        mascara_filtro_x[1 - 1][2 - 1] = 0;
        mascara_filtro_x[1 - 1][3 - 1] = 1;
        mascara_filtro_x[2 - 1][1 - 1] = -k;
        mascara_filtro_x[2 - 1][2 - 1] = 0;
        mascara_filtro_x[2 - 1][3 - 1] = k;
        mascara_filtro_x[3 - 1][1 - 1] = -1;
        mascara_filtro_x[3 - 1][2 - 1] = 0;
        mascara_filtro_x[3 - 1][3 - 1] = 1;

        //GradienteY
        mascara_filtro_y[1 - 1][1 - 1] = -1;
        mascara_filtro_y[1 - 1][2 - 1] = -k;
        mascara_filtro_y[1 - 1][3 - 1] = -1;
        mascara_filtro_y[2 - 1][1 - 1] = 0;
        mascara_filtro_y[2 - 1][2 - 1] = 0;
        mascara_filtro_y[2 - 1][3 - 1] = 0;
        mascara_filtro_y[3 - 1][1 - 1] = 1;
        mascara_filtro_y[3 - 1][2 - 1] = k;
        mascara_filtro_y[3 - 1][3 - 1] = 1;

        std::vector<std::thread> some_threads(nThreads);
        std::vector<std::thread> workers;
        for (int i = 0; i < nThreads; ++i) {
            workers.push_back(std::thread([this](int i){ algoritmo(i);},i));
        }
        std::for_each(workers.begin(), workers.end(), [](std::thread &t)
        {
            t.join();
        });

        delete[] matriz_es;
        delete[] matriz_direccion;
        delete[] matriz_nomax;
        delete[] matriz_visitados;

        delete[] matriz_image;
        delete[] matriz_Jy;
        for(int i = 0; i<3;++i) {
            delete[] mascara_filtro_x[i];
            delete[] mascara_filtro_y[i];
        }

        delete[] mascara_filtro_x;
        delete[] mascara_filtro_y;
    }

    void algoritmo(int my_thread){

        int thread = my_thread;
        int myInitRow = (int)(((float)my_thread/(float)nThreads)*((float)height/2.0f));
        int nextThreadRow = (int)(((float)(my_thread+1)/(float)nThreads)*((float)height/2.0f));
        int myFinish, myInit;
        myInit = 2*width*myInitRow;
        myFinish = 2*width*nextThreadRow;
        image_toGreyParallel(thread, myInit, myFinish);


        int size = width*height;

        int x;
        int i;
        int j;
        //Inicializar matriz_umbral a negro
        for(x =myInit; x<myFinish;x++){
            matriz_umbral[x] = blackPixel;
        }

        //Convolución Jx
        convolucionParallelX(mascara_filtro_x,myInit,myFinish);
        //Convolución Jy
        convolucionParallelY(mascara_filtro_y,myInit,myFinish);

        //Calcular la magnitud de los bordes
        for(x =myInit; x<myFinish;x++){
            matriz_es[x] = sqrt((pow(matriz_Jx[x],2))+(pow(matriz_Jy[x],2)));
        }
        for(x =myInit; x<myFinish;x++){
            matriz_direccion[x] = matriz_Jx[x]==0 ? 0 : direccion_cercanaParallel(atan(matriz_Jy[x] / matriz_Jx[x]));
        }

        //Crear matriz de no máximos
        for(x =myInit; x<myFinish;x++){
            i = x/width;
            j = x-width*i;
            if (i <= 1 || i == height-1 || j <=1 || j == width-1) {
                matriz_nomax[x] = 0;
                continue;
            }
            crear_matriz_nomax_orientacionParallel(x);
        }

        //Generar bordes según orientación
        for(x =myInit; x<myFinish;x++){
            if (matriz_visitados[x] == 1)continue;
            i = x/width;
            j = x-width*i;
            if (i != 0 || i != height || j != width || j != 0) {
                if (matriz_nomax[x] >= u_max) {
                    if (matriz_visitados[x] == 1)continue;	//Píxel ya estudiado
                    if (i == 0 || i == height|| j == width|| j == 0) continue;
                    seguir_cadena_orientacionParallel(x);
                }
            }
        }

        //Juntar contornos
        for(x =myInit; x<myFinish;x++){
            i = x/width;
            j = x-width*i;
            if (i == 0 || i == width || j == 0 || j ==height) continue;
            if (matriz_nomax[x] >= u_max)juntar_contornosParallel(x);
        }

        pthread_exit(NULL);
    }

    void convolucionParallelX(int** m2,int myInit, int myFinish){

        int center = 3 / 2;	//Número de píxeles de margen
        double sumatoria_convolucion;
        int fila;
        int columna;
        int x;
        int i;
        int j;
        for(x =myInit; x<myFinish;x++){
            sumatoria_convolucion = 0.0;
            i = x/width;
            j = x-width*i;
                for (int k = 0; k < 3 ; k++) {
                    for (int l = 0; l < 3 ; l++) {
                        //Se resta center por el tamaño del kernel
                        fila = i + k - center - 1;
                        columna = j + l - center - 1;
                        sumatoria_convolucion += (matriz_image[fila*width+columna]*m2[k][l]);
                    }
                }
            matriz_Jx[i*width+j] = sumatoria_convolucion;
        }
    }

    void convolucionParallelY(int** m2,int myInit, int myFinish){
        int center = 3 / 2;	//Número de píxeles de margen
        double sumatoria_convolucion;
        int fila;
        int columna;
        int x;
        int i;
        int j;
        for(x =myInit; x<myFinish;x++){
            sumatoria_convolucion = 0.0;
            i = x/width;
            j = x-width*i;
                for (int k = 0; k < 3 ; k++) {
                    for (int l = 0; l < 3 ; l++) {
                        //Se resta center por el tamaño del kernel
                        fila = i + k - center - 1;
                        columna = j + l - center - 1;
                        sumatoria_convolucion += (matriz_image[fila*width+columna]*m2[k][l]);
                    }
                }
            matriz_Jy[i*width+j] = sumatoria_convolucion;
        }
    }

    int direccion_cercanaParallel(double f) {
        //Convertir valor en ángulo
        double angulo = (f / M_PI) * 180.0;
        //Comprobar cercanía
        if ((angulo < 22.5 && angulo > -22.5) || (angulo > 157.5 && angulo < -157.5)) return 0;
        if ((angulo > 22.5 && angulo < 67.5) || (angulo < -112.5 && angulo > -157.5)) return 45;
        if ((angulo > 67.5 && angulo < 112.5) || (angulo < -67.5 && angulo > -112.5)) return 90;
        if ((angulo > 112.5 && angulo < 157.5) || (angulo < -22.5 && angulo > -67.5)) return 135;

        return -1;	//No llega aquí
    }

    void crear_matriz_nomax_orientacionParallel(int x) {

        int direccion = matriz_direccion[x];
        switch (direccion) {
            case 0:	//Comprobar con los píxeles de la izquierda y la derecha

                if (matriz_es[x] < matriz_es[x-1] || matriz_es[x] < matriz_es[x + 1]) {
                    matriz_nomax[x] = 0;
                }
                else {
                    matriz_nomax[x] = matriz_es[x];
                }
                break;
            case 45://Comprobar con los píxeles de la izquierda abajo y la derecha arriba
                if (matriz_es[x] < matriz_es[x+width-1] || matriz_es[x] < matriz_es[x-width+1]) {
                    matriz_nomax[x] = 0;
                }
                else {
                    matriz_nomax[x] = matriz_es[x];
                }
                break;
            case 90://Comprobar con los píxeles de arriba y abajo
                if (matriz_es[x] < matriz_es[x - width] || matriz_es[x] < matriz_es[x + width]) {
                    matriz_nomax[x] = 0;
                }
                else {
                    matriz_nomax[x] = matriz_es[x];
                }
                break;
            case 135://Comprobar con los píxeles de la izquierda arriba y la derecha abajo
                if (matriz_es[x] < matriz_es[x - width - 1] || matriz_es[x] < matriz_es[x + width + 1]) {
                    matriz_nomax[x] = 0;
                }
                else {
                    matriz_nomax[x]= matriz_es[x];
                }
                break;
            default:
                matriz_nomax[x] = 0;
                break;
        }
    }

    void seguir_cadena_orientacionParallel(int x) {

        matriz_visitados[x] = 1;	//Visitado
        matriz_umbral[x] = whitePixel;		//Marcado como borde
        //A partir de aquí recorrer píxeles conectados en ambas direcciones perpendiculares
        //a la normal del borde mientras sea > u_min

        //Valores de los dos vecinos
        int aux_x1, aux_y1, aux_x2, aux_y2;

        int newPosition1;
        int newPosition2;
        int direccion = matriz_direccion[x];
        //int direccion = matriz_Jx[i][j]==0 ? direccion_cercana(0) : direccion_cercana(Math.atan(matriz_Jy[i][j] / matriz_Jx[i][j]));
        switch (direccion) {
            case 0:	//Comprobar con los píxeles de la izquierda y la derecha
                newPosition1 = x-1;
                newPosition2 = x+1;
                break;
            case 45://Comprobar con los píxeles de la izquierda abajo y la derecha arriba
                newPosition1 = x+width-1;
                newPosition2 = x-width+1;
                break;
            case 90://Comprobar con los píxeles de arriba y abajo
                newPosition1 = x-width;
                newPosition2 = x+width;
                break;
            case 135://Comprobar con los píxeles de la izquierda arriba y la derecha abajo
                newPosition1 = x-width-1;
                newPosition2 = x+width+1;
                break;
            default:
                newPosition1 = x;
                newPosition2 = x;
                break;
        }

        //Seguir cadena por los puntos donde el valor sea mayor al umbral mínimo
        if (matriz_nomax[newPosition1] >= u_min) {
            if (matriz_visitados[newPosition1] == 1) return;	//Píxel ya estudiado
            int i = newPosition1/width;
            int j = newPosition1-width*i;
            if (i + aux_x1 == 0 || i + aux_x1 == height|| j + aux_y1 == width|| j + aux_y1 == 0) return;
            seguir_cadena_orientacionParallel(newPosition1);
        }

        if (matriz_nomax[newPosition2] >= u_min) {
            if (matriz_visitados[newPosition2] == 1) return;	//Píxel ya estudiado
            int i = newPosition2/width;
            int j = newPosition2-width*i;
            if (i + aux_x2 == 0 || i + aux_x2 == height|| j + aux_y2 == width|| j + aux_y2 == 0) return;
            seguir_cadena_orientacionParallel(newPosition2);
        }
    }

    void juntar_contornosParallel(int x) {

        //Recorrer imagen original con una máscara 3x3 y comprobar si hay algún borde fuerte
        //Comprobar vecinos

        //Izquierda arriba
        if (matriz_nomax[x-width-1] >= u_min) matriz_umbral[x] = whitePixel;
        //Arriba
        if (matriz_nomax[x-width] >= u_min) matriz_umbral[x] = whitePixel;
        //Derecha arriba
        if (matriz_nomax[x-width+1] >= u_min) matriz_umbral[x] = whitePixel;
        //Izquierda
        if (matriz_nomax[x-1] >= u_min) matriz_umbral[x] = whitePixel;
        //Derecha
        if (matriz_nomax[x+1] >= u_min) matriz_umbral[x] = whitePixel;
        //Izquierda abajo
        if (matriz_nomax[x+width-1] >= u_min) matriz_umbral[x] = whitePixel;
        //Abajo
        if (matriz_nomax[x+width] >= u_min) matriz_umbral[x] = whitePixel;
        //Derecha abajo
        if (matriz_nomax[x+width+1] >= u_min) matriz_umbral[x] = whitePixel;

        /*for (int k = -1; k <= 1; k++) {
            for (int l = -1; l <= 1; l++) {
                if (k == 0 && l == 0) continue;
                if (matriz_nomax[i + k][j + l] >= u_min) matriz_umbral[x] = whitePixel;
            }
        }*/
    }

    void image_toGreyParallel(int my_id, int myOff, int myEnd) {
        int y1;
        int i;
        for(i=myOff; i < myEnd;i++) {
            y1 = data[i ]&0xff;
            matriz_image[i] = 0xff000000 | (y1<<16) | (y1<<8) | y1;
        }
    }

public: int* getMatriz_umbralParallel() {
        return matriz_umbral;
    }
public: void free_matrix(){
        delete[] matriz_umbral;
    }
};
