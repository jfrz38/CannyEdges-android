package com.example.procimagencamara;

import java.sql.Timestamp;
import java.time.chrono.ThaiBuddhistEra;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class CannyEdgesParallel {

    private double[][] matriz_es;	//Matriz de magnitud de los bordes
    //private double[][] matriz_eo;	//Matriz de orientación de los bordes
    private int[][] matriz_direccion;	//Matriz de dirección de los bordes
    private double[][] matriz_nomax;	//Matriz de no máximos
    private int[][] matriz_visitados;	//Matriz de píxeles visitados
    private int[][] matriz_umbral;	//Matriz binaria resultante de la umbralización
    private int width;
    private int height;
    private double[][] matriz_Jx;	//Matriz convolucionada con máscara X
    private double[][] matriz_Jy;	//Mátriz convolucionada con máscara Y
    private double[][] matriz_J;
    private double[][] kernel_gauss;
    int u_max = 250*50000; //Umbral máximo
    int u_min = 200*50000; //Umbral mínimo
    private double[][] mascara_filtro_x;	//Máscara genérica X
    private double[][] mascara_filtro_y;	//Máscara genérica Y
    public int blackPixel = (255<<24) | (0<<16) | (0<<8) | 0;
    public int whitePixel = (255<<24) | (255<<16) | (255<<8) | 255;
    public int nThreads;
    public int[][] matriz_image;
    private int[] image;
    private byte[] data;
    private int[] matriz_return;

    public CannyEdgesParallel(int width, int height, byte[] data, int nThreads){

        this.width = width;
        this.height = height;
        this.nThreads = nThreads;

        //Inicializar matrices
        matriz_es = new double[height][width];
        //matriz_eo = new double[height][width];
        matriz_direccion = new int[height][width];
        matriz_nomax = new double[height][width];
        matriz_visitados = new int[height][width];
        matriz_umbral = new int[height][width];
        mascara_filtro_x = new double[3][3];
        mascara_filtro_y = new double[3][3];
        image = new int[height*width];
        matriz_image = new int[height][width];
        matriz_return = new int[width*height];
        matriz_Jx = new double[height][width];
        matriz_Jy = new double[height][width];
        //this.image = image;
        this.data = data;

        int k = 2;
        //GradienteX
        mascara_filtro_x[1-1][1-1] = -1;		mascara_filtro_x[1-1][2-1] = 0;		mascara_filtro_x[1-1][3-1] = 1;
        mascara_filtro_x[2-1][1-1] = -k;		mascara_filtro_x[2-1][2-1] = 0;		mascara_filtro_x[2-1][3-1] = k;
        mascara_filtro_x[3-1][1-1] = -1;		mascara_filtro_x[3-1][2-1] = 0;		mascara_filtro_x[3-1][3-1] = 1;

        //GradienteY
        mascara_filtro_y[1-1][1-1] = -1;		mascara_filtro_y[1-1][2-1] = -k;	mascara_filtro_y[1-1][3-1] = -1;
        mascara_filtro_y[2-1][1-1] = 0;			mascara_filtro_y[2-1][2-1] = 0;		mascara_filtro_y[2-1][3-1] = 0;
        mascara_filtro_y[3-1][1-1] = 1;			mascara_filtro_y[3-1][2-1] = k;		mascara_filtro_y[3-1][3-1] = 1;

        List<Callable<Void>> todoTasks = new ArrayList<>(nThreads);
        //ExecutorService es = Executors.newFixedThreadPool(nThreads);
        for (int i=0; i < nThreads; i++) {
            todoTasks.add(i, new myJavaWorker(i));
        }
        try {
            Executors.newFixedThreadPool(nThreads).invokeAll(todoTasks);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void algoritmo(int thread){

        int myInit = width/nThreads*thread;
        int myFinish = width/nThreads*(thread+1);

        image_toGrey(thread);

        //System.out.println("INICIO convertir imagen en matriz : "+new Timestamp(System.currentTimeMillis()));
        imageToMatrix(width, height,myInit,myFinish);
        //System.out.println("FIN convertir imagen en matriz : "+new Timestamp(System.currentTimeMillis()));

        //System.out.println("INICIO convolución Jx y Jy : "+new Timestamp(System.currentTimeMillis()));

        //Convolución Jx
        convolucionX(mascara_filtro_x,myInit,myFinish);
        //Convolución Jy
        convolucionY(mascara_filtro_y,myInit,myFinish);
        //System.out.println("FIN convolución Jx y Jy : "+new Timestamp(System.currentTimeMillis()));

        //Calcular la magnitud de los bordes
        //System.out.println("INICIO calcular magnitud de los bordes : "+new Timestamp(System.currentTimeMillis()));
        int x;
        for(int i=0;i < height - 1 ;i++){
            for(int j = myInit; j < myFinish; j++) {
                matriz_es[i][j] = Math.sqrt((Math.pow(matriz_Jx[i][j],2))+(Math.pow(matriz_Jy[i][j],2)));
            }
        }
        //System.out.println("FIN calcular magnitud de los bordes : "+new Timestamp(System.currentTimeMillis()));

        //System.out.println("INICIO estimar orientación de los bordes : "+new Timestamp(System.currentTimeMillis()));
        //Estimar la orientación de la normal de los bordes
        for(int i=0;i < height - 1 ;i++){
            for(int j = myInit; j < myFinish; j++) {
                matriz_direccion[i][j] = matriz_Jx[i][j]==0 ? direccion_cercana(0) : direccion_cercana(Math.atan(matriz_Jy[i][j] / matriz_Jx[i][j]));
            }
        }
        //System.out.println("FIN estimar orientación de los bordes : "+new Timestamp(System.currentTimeMillis()));

        //System.out.println("INICIO crear matriz de orientación : "+new Timestamp(System.currentTimeMillis()));
        //Matriz no_max vecino más cercano
        for(int i=0;i < height - 1 ;i++){
            for(int j = myInit; j < myFinish; j++) {
                crear_matriz_nomax_orientacion(i, j);
            }
        }
        //System.out.println("FIN crear matriz de orientación : "+new Timestamp(System.currentTimeMillis()));

        //System.out.println("INICIO histéresis del humbral : "+new Timestamp(System.currentTimeMillis()));
        //Histéresis según el umbral
        for(int i=0;i < height - 1 ;i++){
            for(int j = myInit; j < myFinish; j++) {
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

        //System.out.println("FIN histéresis del humbral : "+new Timestamp(System.currentTimeMillis()));

        //System.out.println("INICIO unión de bordes : "+new Timestamp(System.currentTimeMillis()));
        //Unión de bordes
        for(int i=0;i < height - 1 ;i++){
            for(int j=myInit; j < myFinish -1; j++) {
                if (matriz_nomax[i][j] >= u_max){
                    if (i == 0 || i == width || j == 0 || j ==height) continue;
                    juntar_contornos(i, j);
                }
            }
        }
        //System.out.println("FIN unión de bordes : "+new Timestamp(System.currentTimeMillis()));

        getMatriz_umbralOneVector(myInit,myFinish);
    }

    public int direccion_cercana(double f) {

        //Convertir valor en ángulo
        double angulo = (f / Math.PI) * 180.0;
        //Comprobar cercanía
        if ((angulo < 22.5 && angulo > -22.5) || (angulo > 157.5 && angulo < -157.5)) return 0;
        if ((angulo > 22.5 && angulo < 67.5) || (angulo < -112.5 && angulo > -157.5)) return 45;
        if ((angulo > 67.5 && angulo < 112.5) || (angulo < -67.5 && angulo > -112.5)) return 90;
        if ((angulo > 112.5 && angulo < 157.5) || (angulo < -22.5 && angulo > -67.5)) return 135;

        return -1;	//No llega aquí
    }

    public void crear_matriz_nomax_orientacion(int i, int j) {

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

    public void seguir_cadena_orientacion(int i, int j) {

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

    public void juntar_contornos(int i, int j) {

        //if (i == 0 || i == width || j == 0 || j == height) return;

        //Recorrer imagen original con una máscara 3x3 y comprobar si hay algún borde fuerte
        for (int k = -1; k <= 1; k++) {
            for (int l = -1; l <= 1; l++) {
                if (k == 0 && l == 0) continue;
                if (matriz_nomax[i + k][j + l] >= u_min) matriz_umbral[i][j] = 255;
            }
        }
    }

    public int[] getMatriz_umbral() {
        return matriz_return;
    }

    public void getMatriz_umbralOneVector(int myInit, int myFinish) {
        int x;
        for(int i=1;i < height - 1 ;i++) {
            for(int j = myInit; j < myFinish; j++) {
                x = i * width + j;
                matriz_return[x] = matriz_umbral[i][j] == 255 ? whitePixel : blackPixel;
            }
        }
        //return  matriz_return;
    }

    public void imageToMatrix(int width, int height, int myInit, int myFinish){
        int x;
        for(int i=1;i < height - 1 ;i++) {
            for(int j = myInit; j < myFinish; j++) {
                x = i * width + j;
                matriz_image[i][j] = image[x];
            }
        }
    }

    public void convolucionX(double[][]m2,int myInit, int myFinish){

        int center = m2.length / 2;	//Número de píxeles de margen
        double sumatoria_convolucion;
        //Recorrer matriz imagen original
        for (int i = 0; i < height; i++) {
            for(int j = myInit; j < myFinish; j++) {
                sumatoria_convolucion = 0.0;
                if (i <= center || i > (matriz_image.length - center) || j <= center || j > (matriz_image[0].length - center)) {
                    sumatoria_convolucion = matriz_image[i][j];
                }else {
                    for (int k = 0; k < m2.length; k++) {
                        for (int l = 0; l < m2[0].length; l++) {
                            //Se resta center por el tamaño del kernel
                            sumatoria_convolucion += (matriz_image[i + k - center - 1][j + l - center - 1]*m2[k][l]);
                        }
                    }
                }
                matriz_Jx[i][j] = sumatoria_convolucion;
            }
        }
    }

    public void convolucionY(double[][]m2,int myInit, int myFinish){

        int center = m2.length / 2;	//Número de píxeles de margen
        double sumatoria_convolucion;
        //Recorrer matriz imagen original
        for (int i = 0; i < height; i++) {
            for(int j = myInit; j < myFinish; j++) {
                sumatoria_convolucion = 0.0;
                if (i <= center || i > (matriz_image.length - center) || j <= center || j > (matriz_image[0].length - center)) {
                    sumatoria_convolucion = matriz_image[i][j];
                }else {
                    for (int k = 0; k < m2.length; k++) {
                        for (int l = 0; l < m2[0].length; l++) {
                            //Se resta center por el tamaño del kernel
                            sumatoria_convolucion += (matriz_image[i + k - center - 1][j + l - center - 1]*m2[k][l]);
                        }
                    }
                }
                matriz_Jy[i][j] = sumatoria_convolucion;
            }
        }
    }

    public void image_toGrey(int my_id) {
        //int size = width*height;
        int y1;
        //int offset = size;
        int myInitRow = (int)(((float)my_id/(float)nThreads)*((float)height/2.0f));
        int nextThreadRow = (int)(((float)(my_id+1)/(float)nThreads)*((float)height/2.0f));
        int myEnd, myOff;
        myOff = 2*width*myInitRow;
        myEnd = 2*width*nextThreadRow;
        for(int i=myOff; i < myEnd;i++) {
            y1 = data[i]&0xff;
            image[i] = 0xff000000 | (y1<<16) | (y1<<8) | y1;
        }
    }

    private final class myJavaWorker implements Callable<Void> {

        //private int [] pixels;
        private int id;

        public myJavaWorker(int _id) {

            //pixels = _pixels;
            id = _id;
        }

        public Void call(){
            algoritmo(id);
            return null;
        }
    }



}
