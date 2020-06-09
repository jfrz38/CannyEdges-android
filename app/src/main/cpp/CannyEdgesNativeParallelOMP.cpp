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

#define blackPixel -16777216
#define whitePixel -1

/*	Programa principal que realiza el procesamiento de la imagen
	entrada[] : nombre de la imagen a procesar
	salida[] : nombre de la imagen creada
*/

class CannyEdgesNativeOMP{

    vector<vector<double>> matriz_es;	//Matriz de magnitud de los bordes
    vector<vector<int>> matriz_direccion;	//Matriz de dirección de los bordes
    vector<vector<double>> matriz_nomax;	//Matriz de no máximos
    vector<vector<int>> matriz_visitados;	//Matriz de píxeles visitados
    vector<vector<int>> matriz_umbral;	//Matriz binaria resultante de la umbralización
    int width;
    int height;
    vector<vector<double>> matriz_Jx;	//Matriz convolucionada con máscara X
    vector<vector<double>> matriz_Jy;	//Mátriz convolucionada con máscara Y
    int u_max = 250*10000; //Umbral máximo
    int u_min = 200*10000; //Umbral mínimo
    vector<vector<double>> mascara_filtro_x;	//Máscara genérica X
    vector<vector<double>> mascara_filtro_y;	//Máscara genérica Y
    //int blackPixel = (255<<24) | (0<<16) | (0<<8) | 0;
    //int whitePixel = (255<<24) | (255<<16) | (255<<8) | 255;

public:
    CannyEdgesNativeOMP(int width, int height, unsigned char* data){
        this->width = width;
        this->height = height;
        //Inicializar matrices
        matriz_es.resize(height,vector<double>(width));
        matriz_direccion.resize(height, vector<int> (width));
        matriz_nomax.resize(height, vector<double> (width));
        matriz_visitados.resize(height, vector<int> (width));
        matriz_umbral.resize(height, vector<int> (width));
        vector<vector<int>> mascara_filtro_x(3, vector<int> (3, 0));
        vector<vector<int>> mascara_filtro_y(3, vector<int> (3, 0));

        vector<vector<int>> matriz_image = imageToMatrix(convertYUV420_NV21toGrey(data,width,height), width, height);

        int k = 2;
        //GradienteX
        mascara_filtro_x[1-1][1-1] = -1;		mascara_filtro_x[1-1][2-1] = 0;		mascara_filtro_x[1-1][3-1] = 1;
        mascara_filtro_x[2-1][1-1] = -k;		mascara_filtro_x[2-1][2-1] = 0;		mascara_filtro_x[2-1][3-1] = k;
        mascara_filtro_x[3-1][1-1] = -1;		mascara_filtro_x[3-1][2-1] = 0;		mascara_filtro_x[3-1][3-1] = 1;

        //GradienteY
        mascara_filtro_y[1-1][1-1] = -1;		mascara_filtro_y[1-1][2-1] = -k;	mascara_filtro_y[1-1][3-1] = -1;
        mascara_filtro_y[2-1][1-1] = 0;			mascara_filtro_y[2-1][2-1] = 0;		mascara_filtro_y[2-1][3-1] = 0;
        mascara_filtro_y[3-1][1-1] = 1;			mascara_filtro_y[3-1][2-1] = k;		mascara_filtro_y[3-1][3-1] = 1;

        getMatriz_umbralOneVector();

        //Convolución Jx
        matriz_Jx = convolucion(matriz_image, mascara_filtro_x);
        //Convolución Jy
        matriz_Jy = convolucion(matriz_image, mascara_filtro_y);

        //Calcular la magnitud de los bordes
        int x,j;
        for(int i=0;i < height - 1 ;i++){
#pragma omp parallel for schedule(guided) private(j)
            for(j=0; j < width -1; j++) {
                matriz_es[i][j] = sqrt((pow(matriz_Jx[i][j],2))+(pow(matriz_Jy[i][j],2)));
            }
        }

        //Estimar la orientación de la normal de los bordes
        for(int i=0;i < height - 1 ;i++){
#pragma omp parallel for schedule(guided) private(j)
            for(j=0; j < width -1; j++) {
                matriz_direccion[i][j] = matriz_Jx[i][j]==0 ? direccion_cercana(0) : direccion_cercana(atan(matriz_Jy[i][j] / matriz_Jx[i][j]));
            }
        }

        //Matriz no_max vecino más cercano
        for(int i=0;i < height - 1 ;i++){
#pragma omp parallel for schedule(guided) private(j)
            for(j=0; j < width -1; j++) {
                crear_matriz_nomax_orientacion(i, j);
            }
        }

        //Histéresis según el umbral
        for(int i=0;i < height - 1 ;i++){
#pragma omp parallel for schedule(guided) private(j)
            for(j=0; j < width -1; j++) {
                //Si se ha visitado el punto continua la ejecución
                if (matriz_visitados[i][j] == 1)continue;
                //Los bordes se dejan igual para evitar problemas al visitar vecinos que estén fuera de rango
                if (i != 0 || i != height || j != width || j != 0) {
                    if (matriz_nomax[i][j] >= u_max) {
                        if (matriz_visitados[i][j] == 1)continue;	//Píxel ya estudiado
                        if (i == 0 || i == height|| j == width|| j == 0) continue;
                        seguir_cadena_orientacion(i,j);
                    }
                }
            }
        }

        //Unión de bordes
        for(int i=0;i < height - 1 ;i++){
#pragma omp parallel for schedule(guided) private(j)
            for(j=0; j < width -1; j++) {
                if (matriz_nomax[i][j] >= u_max)juntar_contornos(i, j);
            }
        }
    }


    vector<vector<double>> convolucion(vector<vector<int>> m1, vector<vector<int>> m2) {

        int center = m2.size() / 2;	//Número de píxeles de margen
        vector<vector<double>> aux(height, vector<double> (width, 0));
        double sumatoria_convolucion;
        int j;
        //Recorrer matriz imagen original
        for (int i = 0; i < height; i++) {
#pragma omp parallel for schedule(guided) private(j)
            for (j = 0; j < width; j++) {
                sumatoria_convolucion = 0;
                //Si el punto se encuentra dentro del margen se deja el píxel con el valor actual y se continua la ejecución
                if (i <= center || i > (m1.size() - center) || j <= center || j > (m1[0].size() - center)) {
                    sumatoria_convolucion = m1[i][j];
                }
                    //En caso contrario se realiza la convolución
                else {
                    for (int k = 0; k < m2.size(); k++) {
                        for (int l = 0; l < m2[0].size(); l++) {
                            //Se resta center por el tamaño del kernel
                            sumatoria_convolucion += m1[i + k - center - 1][j + l - center - 1]*m2[k][l];
                        }
                    }
                }
                //Se añade el valor calculado al punto correspondiente
                aux[i][j] = sumatoria_convolucion;
            }
        }

        return aux;
    }

    int direccion_cercana(double f) {

        //Convertir valor en ángulo
        double angulo = (f / M_PI) * 180.0;
        //Comprobar cercanía
        if ((angulo < 22.5 && angulo > -22.5) || (angulo > 157.5 && angulo < -157.5)) return 0;
        if ((angulo > 22.5 && angulo < 67.5) || (angulo < -112.5 && angulo > -157.5)) return 45;
        if ((angulo > 67.5 && angulo < 112.5) || (angulo < -67.5 && angulo > -112.5)) return 90;
        if ((angulo > 112.5 && angulo < 157.5) || (angulo < -22.5 && angulo > -67.5)) return 135;

        return -1;	//No llega aquí
    }

    void crear_matriz_nomax_orientacion(int i, int j) {
        //Evitar píxeles cercanos al borde para no salirse del rango de la imagen al bucar sus vecinos
        if (i <= 1 || i >= height-1 || j <=1 || j >= width-1) {
            matriz_nomax[i][j] = 0;
            return;
        }

        int direccion = matriz_direccion[i][j];
        switch (direccion) {
            case 0:	//Comprobar con los píxeles de la izquierda y la derecha

                if (matriz_es[i][j] < matriz_es[i][j - 1] || matriz_es[i][j] < matriz_es[i][j + 1]) {
                    matriz_nomax[i][j] = 0;
                }
                else {
                    matriz_nomax[i][j] = matriz_es[i][j];
                }
                break;
            case 45://Comprobar con los píxeles de la izquierda abajo y la derecha arriba
                if (matriz_es[i][j] < matriz_es[i - 1][j + 1] || matriz_es[i][j] < matriz_es[i + 1][j - 1]) {
                    matriz_nomax[i][j] = 0;
                }
                else {
                    matriz_nomax[i][j] = matriz_es[i][j];
                }
                break;
            case 90://Comprobar con los píxeles de arriba y abajo
                if (matriz_es[i][j] < matriz_es[i - 1][j] || matriz_es[i][j] < matriz_es[i + 1][j]) {
                    matriz_nomax[i][j] = 0;
                }
                else {
                    matriz_nomax[i][j] = matriz_es[i][j];
                }
                break;
            case 135://Comprobar con los píxeles de la izquierda arriba y la derecha abajo
                if (matriz_es[i][j] < matriz_es[i - 1][j - 1] || matriz_es[i][j] < matriz_es[i + 1][j + 1]) {
                    matriz_nomax[i][j] = 0;
                }
                else {
                    matriz_nomax[i][j]= matriz_es[i][j];
                }
                break;
            default:
                matriz_nomax[i][j] = 0;
                break;
        }
    }

    void seguir_cadena_orientacion(int i, int j) {

        //if (matriz_visitados[i][j] == 1)return;	//Píxel ya estudiado
        //if (i == 0 || i == height|| j == width|| j == 0) return;

        matriz_visitados[i][j] = 1;	//Visitado
        matriz_umbral[i][j] = 255;		//Marcado como borde

        //A partir de aquí recorrer píxeles conectados en ambas direcciones perpendiculares
        //a la normal del borde mientras sea > u_min

        //Valores de los dos vecinos
        int aux_x1, aux_y1, aux_x2, aux_y2;

        int direccion = matriz_direccion[i][j];
        //int direccion = matriz_Jx[i][j]==0 ? direccion_cercana(0) : direccion_cercana(Math.atan(matriz_Jy[i][j] / matriz_Jx[i][j]));
        switch (direccion) {
            case 0:	//Comprobar con los píxeles de la izquierda y la derecha
                aux_x1 = 0; aux_x2 = 0; aux_y1 = -1; aux_y2 = 1;
                break;
            case 45://Comprobar con los píxeles de la izquierda abajo y la derecha arriba
                aux_x1 = -1; aux_x2 = 1; aux_y1 = -1; aux_y2 = -1;
                break;
            case 90://Comprobar con los píxeles de arriba y abajo
                aux_x1 = -1; aux_x2 = 1; aux_y1 = 0; aux_y2 = 0;
                break;
            case 135://Comprobar con los píxeles de la izquierda arriba y la derecha abajo
                aux_x1 = -1; aux_x2 = 1; aux_y1 = -1; aux_y2 = 1;
                break;
            default:
                aux_x1 = 0; aux_x2 = 0; aux_y1 = 0; aux_y2 = 0;
                break;
        }
        //Seguir cadena por los puntos donde el valor sea mayor al umbral mínimo
        //System.out.println("Valor nomax en u_min = "+matriz_nomax[i + aux_x1][j + aux_y1]+" ; "+matriz_nomax[i + aux_x2][j + aux_y2]+" ; u_min = "+u_min);
        if (matriz_nomax[i + aux_x1][j + aux_y1] >= u_min) {
            if (matriz_visitados[i + aux_x1][j + aux_y1] == 1) return;	//Píxel ya estudiado
            if (i + aux_x1 == 0 || i + aux_x1 == height|| j + aux_y1 == width|| j + aux_y1 == 0) return;

            seguir_cadena_orientacion(i + aux_x1, j + aux_y1);
        }
        if (matriz_nomax[i + aux_x2][j + aux_y2] >= u_min) {
            if (matriz_visitados[i + aux_x2][j + aux_y2] == 1) return;	//Píxel ya estudiado
            if (i + aux_x2 == 0 || i + aux_x2 == height|| j + aux_y2 == width|| j + aux_y2 == 0) return;
            seguir_cadena_orientacion(i + aux_x2, j + aux_y2);
        }
    }

    void juntar_contornos(int i, int j) {

        if (i == 0 || i == width || j == 0 || j ==height) return;

        //Recorrer imagen original con una máscara 3x3 y comprobar si hay algún borde fuerte
        for (int k = -1; k <= 1; k++) {
            for (int l = -1; l <= 1; l++) {
                if (k == 0 && l == 0) continue;
                if (matriz_nomax[i + k][j + l] >= u_min) matriz_umbral[i][j] = 255;
            }
        }
    }


    vector<vector<int>> imageToMatrix(vector<int> image, int width, int height){
        //vector<vector<int>> aux{};
        vector<vector<int>> aux(height, vector<int> (width, 0));
        int x;
        for(int i=1;i < height - 1 ;i++) {
            for (int j = 1; j < width - 1; j++) {
                x = i * width + j;
                aux[i][j] = image[x];
            }
        }
        return aux;
    }

public: vector<int> getMatriz_umbralOneVector() {

        vector<int> matriz_return(width*height);
        int x;
        for(int i=1;i < height - 1 ;i++) {
            for (int j = 1; j < width - 1; j++) {
                x = i * width + j;
                matriz_return[x] = matriz_umbral[i][j] == 255 ? whitePixel : blackPixel;
            }
        }
        return  matriz_return;

        /*
            blackPixel = -16777216
            whitePixel = -1
            redPixel = -65536
            greenPixel = -16711936
            bluePixel = -16776961
         */
    }

    vector<int> convertYUV420_NV21toGrey(const unsigned char* data,int width,int height) {

        vector<int> matriz_return(width*height);
        int size = width*height;
        int y1;
        int i;
        for(i=0; i < size; i++) {
            y1 = data[i ]&0xff;
            matriz_return[i] = 0xff000000 | (y1<<16) | (y1<<8) | y1;
        }

        return matriz_return;
    }
};

