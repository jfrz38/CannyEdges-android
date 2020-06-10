package com.example.procimagencamara;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

public class CannyEdgesParallelV2 {

    private double[] matriz_es;	//Matriz de magnitud de los bordes
    private int[] matriz_direccion;	//Matriz de dirección de los bordes
    private double[] matriz_nomax;	//Matriz de no máximos
    private int[] matriz_visitados;	//Matriz de píxeles visitados
    private int[] matriz_umbral;	//Matriz binaria resultante de la umbralización
    private int width;
    private int height;
    private double[] matriz_Jx;	//Matriz convolucionada con máscara X
    private double[] matriz_Jy;	//Mátriz convolucionada con máscara Y
    private int u_max = 250*10000; //Umbral máximo
    private int u_min = 200*10000; //Umbral mínimo
    private int[][] mascara_filtro_x;	//Máscara genérica X
    private int[][] mascara_filtro_y;	//Máscara genérica Y
    private byte[] data;
    private int blackPixel = (255<<24) | (0<<16) | (0<<8) | 0;
    private int whitePixel = (255<<24) | (255<<16) | (255<<8) | 255;
    private int nThreads;
    private int[] matriz_image;
    private int size;

    public CannyEdgesParallelV2(int width, int height, byte[] data, int nThreads){
        this.width = width;
        this.height = height;
        this.data = data;
        this.nThreads = nThreads;
        this.u_max = 400*10000; //Umbral máximo
        this.u_min = 300*10000; //Umbral mínimo
        size=width*height;

        //Inicializar matrices
        matriz_es = new double[size];
        matriz_direccion = new int[size];
        matriz_nomax = new double[size];
        matriz_visitados = new int[size];
        matriz_umbral = new int[size];
        matriz_image = new int[size];
        matriz_Jx = new double[size];
        matriz_Jy = new double[size];
        mascara_filtro_x = new int[3][3];
        mascara_filtro_y = new int[3][3];

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

        List<Callable<Void>> todoTasks = new ArrayList<>(nThreads);
        for (int i=0; i < nThreads; i++) {
            todoTasks.add(i, new CannyEdgesParallelV2.myJavaWorker(i));
        }
        try {
            Executors.newFixedThreadPool(nThreads).invokeAll(todoTasks);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void algoritmo(int thread){

        int myInit = size/nThreads*thread;
        int myFinish = size/nThreads*(thread+1);
        int x,i,j;
        image_toGrey(myInit,myFinish);

        //Inicializar matriz_umbral a negro
        for(x =myInit; x<myFinish;x++){
            matriz_umbral[x] = blackPixel;
        }
        //Convolución Jx
        convolucionX(mascara_filtro_x,myInit,myFinish);
        //Convolución Jy
        convolucionY(mascara_filtro_y,myInit,myFinish);

        //Calcular la magnitud de los bordes
        for(x =myInit; x<myFinish;x++){
            matriz_es[x] = Math.sqrt((matriz_Jx[x]*matriz_Jx[x])+(matriz_Jy[x]*matriz_Jy[x]));
        }
        for(x =myInit; x<myFinish;x++){
            matriz_direccion[x] = matriz_Jx[x]==0 ? 0 : direccion_cercana(Math.atan(matriz_Jy[x] / matriz_Jx[x]));
        }
        //Crear matriz de no máximos
        crear_matriz_nomax_orientacion(myInit, myFinish);

        //Generar bordes según orientación
        for(x =myInit; x<myFinish;x++){
            if (matriz_visitados[x] == 1)continue;
            i = x/width;
            j = x-width*i;
            if (i != 0 || i != height || j != width || j != 0) {
                if (matriz_nomax[x] >= u_max) {
                    if (matriz_visitados[x] == 1)continue;	//Píxel ya estudiado
                    seguir_cadena_orientacion(x);
                }
            }
        }

        //Juntar contornos
        juntar_contornos(myInit,myFinish);
    }

    private void juntar_contornos(int myInit,int myFinish) {

        int i,j,x;
        for(x =myInit; x<myFinish;x++){
            i = x/width;
            j = x-width*i;
            if (i == 0 || i == width || j == 0 || j ==height) continue;
            if (matriz_nomax[x] >= u_max){
                //Recorrer imagen original con una máscara 3x3 y comprobar si hay algún borde fuerte
                //Comprobar vecinos
                //Izquierda arriba
                if (matriz_nomax[x-width-1] >= u_min) matriz_umbral[x] = whitePixel; if(x==size-1)System.out.println("1: Último punto blanco");
                //Arriba
                if (matriz_nomax[x-width] >= u_min) matriz_umbral[x] = whitePixel;if(x==size-1)System.out.println("1: Último punto blanco");
                //Derecha arriba
                if (matriz_nomax[x-width+1] >= u_min) matriz_umbral[x] = whitePixel;if(x==size-1)System.out.println("1: Último punto blanco");
                //Izquierda
                if (matriz_nomax[x-1] >= u_min) matriz_umbral[x] = whitePixel;if(x==size-1)System.out.println("1: Último punto blanco");
                //Derecha
                if (matriz_nomax[x+1] >= u_min) matriz_umbral[x] = whitePixel;if(x==size-1)System.out.println("1: Último punto blanco");
                //Izquierda abajo
                if (matriz_nomax[x+width-1] >= u_min) matriz_umbral[x] = whitePixel;if(x==size-1)System.out.println("1: Último punto blanco");
                //Abajo
                if (matriz_nomax[x+width] >= u_min) matriz_umbral[x] = whitePixel;if(x==size-1)System.out.println("1: Último punto blanco");
                //Derecha abajo
                if (matriz_nomax[x+width+1] >= u_min) matriz_umbral[x] = whitePixel;if(x==size-1)System.out.println("1: Último punto blanco");
            }
        }

    }

    private void seguir_cadena_orientacion(int x) {
        matriz_visitados[x] = 1;	//Visitado
        matriz_umbral[x] = whitePixel;		//Marcado como borde
        //A partir de aquí recorrer píxeles conectados en ambas direcciones perpendiculares
        //a la normal del borde mientras sea > u_min

        int newPosition1;
        int newPosition2;
        int direccion = matriz_direccion[x];
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
            if (i == 0 || i == height|| j == width|| j == 0) return;
            seguir_cadena_orientacion(newPosition1);
        }

        if (matriz_nomax[newPosition2] >= u_min) {
            if (matriz_visitados[newPosition2] == 1) return;	//Píxel ya estudiado
            int i = newPosition2/width;
            int j = newPosition2-width*i;
            if (i == 0 || i == height|| j == width|| j == 0) return;
            seguir_cadena_orientacion(newPosition2);
        }
    }

    private void crear_matriz_nomax_orientacion(int myInit, int myFinish) {

        int x,i,j;
        for(x =myInit; x<myFinish;x++){
            i = x/width;
            j = x-width*i;
            if (i <= 1 || i == height-1 || j <=1 || j == width-1) {
                matriz_nomax[x] = 0;
                continue;
            }
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



    }

    private int direccion_cercana(double f) {
        double angulo = (f / 3.14159265358979323846) * 180.0;
        //Comprobar cercanía
        if ((angulo < 22.5 && angulo > -22.5) || (angulo > 157.5 && angulo < -157.5)) return 0;
        if ((angulo > 22.5 && angulo < 67.5) || (angulo < -112.5 && angulo > -157.5)) return 45;
        if ((angulo > 67.5 && angulo < 112.5) || (angulo < -67.5 && angulo > -112.5)) return 90;
        if ((angulo > 112.5 && angulo < 157.5) || (angulo < -22.5 && angulo > -67.5)) return 135;

        return -1;	//No llega aquí
    }

    private void convolucionY(int[][] m2, int myInit, int myFinish) {
        double sumatoria_convolucion;
        int minimumPosition = width+2;
        int maximumPosition = size-width-2;
        int x;
        for(x =myInit; x<myFinish;x++){

            //Si el punto se encuentra dentro del margen se deja el píxel con el valor actual y se continua la ejecución
            if(x<minimumPosition || x>maximumPosition){
                sumatoria_convolucion = matriz_image[x];
            }
            //En caso contrario se realiza la convolución
            else {
                sumatoria_convolucion = (matriz_image[x-width-1]*m2[0][0]);
                sumatoria_convolucion += (matriz_image[x-width]*m2[1][0]);
                sumatoria_convolucion += (matriz_image[x-width+1]*m2[2][0]);
                sumatoria_convolucion += (matriz_image[x-1]*m2[0][1]);
                sumatoria_convolucion += (matriz_image[x+1]*m2[2][1]);
                sumatoria_convolucion += (matriz_image[x+width-1]*m2[0][2]);
                sumatoria_convolucion += (matriz_image[x+width]*m2[1][2]);
                sumatoria_convolucion += (matriz_image[x+width+1]*m2[2][2]);
            }
            //Se añade el valor calculado al punto correspondiente
            matriz_Jy[x] = sumatoria_convolucion;
        }
    }

    private void convolucionX(int[][] m2, int myInit, int myFinish) {

        double sumatoria_convolucion;
        int minimumPosition = width+2;
        int maximumPosition = size-width-2;
        int x;
        for(x =myInit; x<myFinish;x++){

            //Si el punto se encuentra dentro del margen se deja el píxel con el valor actual y se continua la ejecución
            if(x<minimumPosition || x>maximumPosition){
                sumatoria_convolucion = matriz_image[x];
            }
            //En caso contrario se realiza la convolución
            else {
                sumatoria_convolucion = (matriz_image[x-width-1]*m2[0][0]);
                sumatoria_convolucion += (matriz_image[x-width]*m2[1][0]);
                sumatoria_convolucion += (matriz_image[x-width+1]*m2[2][0]);
                sumatoria_convolucion += (matriz_image[x-1]*m2[0][1]);
                sumatoria_convolucion += (matriz_image[x+1]*m2[2][1]);
                sumatoria_convolucion += (matriz_image[x+width-1]*m2[0][2]);
                sumatoria_convolucion += (matriz_image[x+width]*m2[1][2]);
                sumatoria_convolucion += (matriz_image[x+width+1]*m2[2][2]);
            }
            //Se añade el valor calculado al punto correspondiente
            matriz_Jx[x] = sumatoria_convolucion;
        }
    }

    private void image_toGrey(int myOff, int myEnd) {
        int y1;
        int i;
        for(i=myOff; i < myEnd;i++) {
            y1 = data[i]&0xff;
            matriz_image[i] = 0xff000000 | (y1<<16) | (y1<<8) | y1;
        }
    }

    public int[] getMatriz_umbral(){
        return matriz_umbral;
    }

    private final class myJavaWorker implements Callable<Void> {
        private int id;

        public myJavaWorker(int _id) {
            id = _id;
        }

        public Void call(){
            algoritmo(id);
            return null;
        }
    }

}
