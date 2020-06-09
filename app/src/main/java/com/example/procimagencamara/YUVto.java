package com.example.procimagencamara;

import android.graphics.Color;
import android.graphics.YuvImage;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collections;

public class YUVto {

    private static int convertYUVtoRGB(int y, int u, int v) {
        int r,g,b;
        r = y + (int)1.14f*v;
        g = y - (int)(0.395f*u +0.581f*v);
        b = y + (int)2.033f*u;
        r = r>255? 255 : r<0 ? 0 : r;
        g = g>255? 255 : g<0 ? 0 : g;
        b = b>255? 255 : b<0 ? 0 : b;
// un byte para cada valor de alfa, blue, green y red.
        return 0xff000000 | (r<<16) | (g<<8) | b;
    }
    public static int [] convertYUV420_NV21toRGB8888(byte [] data, int width, int height) {
// Los pixeles son enteros de width*height https://en.wikipedia.org/wiki/YUV
        int[] pixels = new int[data.length];
        int size = width*height;
        int u, v, y1, y2, y3, y4;
// i traverses Y. k traverses U and V
        for(int i=0, k=0; i < size; i+=2, k+=2) {
// cada región de 4x4 en el mapa de bits tiene el mismo (u,v) pero diferentes y1,y2,y3,y4
            y1 = data[i ]&0xff;
            y2 = data[i+1]&0xff;
            y3 = data[width+i ]&0xff;
            y4 = data[width+i+1]&0xff;
            v = data[size+k ]&0xff;
            u = data[size+k+1]&0xff;
            u = u-128;
            v = v-128;
            pixels[i ] = convertYUVtoRGB(y1, u, v);
            pixels[i+1] = convertYUVtoRGB(y2, u, v);
            pixels[width+i ] = convertYUVtoRGB(y3, u, v);
            pixels[width+i+1] = convertYUVtoRGB(y4, u, v);
            if (i!=0 && (i+2)%width==0) i+=width;
        }

        return pixels;
    }

    public static int [] convertYUV420_NV21toGrey(byte [] data, int width, int height) {
        int[] pixels = new int[data.length];
        int size = width*height;
        int y1;
        for(int i=0; i < size; i++) {
            y1 = data[i]&0xff;
            pixels[i] = 0xff000000 | (y1<<16) | (y1<<8) | y1;
        }

        return pixels;
    }
    public static int[] convertYUV420_NV21toConvolution(byte [] data, byte[] matrix, int divisor, int width, int height){

        //int size = width*height;
        int color;
        int nextRow, previousRow, x;
        int[] aux = new int[data.length];
// recorre la imagen excepto los bordes
        for(int i=1;i < height - 1 ;i++){
            for(int j=1; j < width -1; j++){
                x = i * width + j;
                nextRow = x + width;
                previousRow = x - width;
                color = matrix[0] * ((int)data[previousRow-1]&0xff) +
                        matrix[1] * ((int)data[previousRow]&0xff) +
                        matrix[2] * ((int)data[previousRow+1]&0xff) +
                        matrix[3] * ((int)data[x-1]&0xff) +
                        matrix[4] * ((int)data[x]&0xff) +
                        matrix[5] * ((int)data[x+1]&0xff) +
                        matrix[6] * ((int)data[nextRow-1]&0xff) +
                        matrix[7] * ((int)data[nextRow]&0xff) +
                        matrix[8] * ((int)data[nextRow+1]&0xff);
                color=(int)((float) color / (float) divisor);
                color = color>255? 255 : color<0 ? 0 : color;
// asigna el mismo color en RGB y valor alfa a 1
                aux[x] = 0xff000000 | (color<<16) | (color<<8) | color;
            }
        }
        return aux;
        //return procImage;
    }

    public static int[] convertYUV420_NV21toCanny(byte[] data, int width, int height) {

        return new CannyEdges(width,height, convertYUV420_NV21toGrey(data,width,height)).getMatriz_umbralOneVector();
    }

    public static int createPixel(int r, int g, int b, int a) {
        return (a<<24) | (r<<16) | (g<<8) | b;
    }

    public static int[] convolutionX(byte [] data, byte[] matrix, int divisor, int width, int height, int mask){

        int size = width*height;
        int color;
        int nextRow, previousRow, x;
        int[] aux = new int[data.length];
// recorre la imagen excepto los bordes
        for(int i=1;i < height - 1 ;i++){
            for(int j=1; j < width -1; j++){
                x = i * width + j;
                nextRow = x + width;
                previousRow = x - width;
                color = matrix[0] * ((int) data[previousRow-1]&0xff) +
                        matrix[1] * ((int)data[previousRow]&0xff) +
                        matrix[2] * ((int)data[previousRow+1]&0xff) +
                        matrix[3] * ((int)data[x-mask]&0xff) +
                        matrix[4] * ((int)data[x]&0xff) +
                        matrix[5] * ((int)data[x+mask]&0xff) +
                        matrix[6] * ((int)data[nextRow-1]&0xff) +
                        matrix[7] * ((int)data[nextRow]&0xff) +
                        matrix[8] * ((int)data[nextRow+1]&0xff);
                color=(int)((float) color / (float) divisor);
                color = color>255? 255 : color<0 ? 0 : color;
// asigna el mismo color en RGB y valor alfa a 1
                aux[x] = 0xff000000 | (color<<16) | (color<<8) | color;
            }
        }

        return aux;//procImage;
    }

    public static int[] convolutionY(byte [] data, byte[] matrix, int divisor, int width, int height, int mask){

        int size = width*height;
        int color;
        int nextRow, previousRow, x;
        int[] aux = new int[data.length];
// recorre la imagen excepto los bordes
        for(int i=1;i < height - 1 ;i++){
            for(int j=1; j < width -1; j++){
                x = i * width + j;
                nextRow = x + width;
                previousRow = x - width;
                color = matrix[0] * ((int) data[previousRow-1]&0xff) +
                        matrix[1] * ((int)data[previousRow-mask]&0xff) +
                        matrix[2] * ((int)data[previousRow-1]&0xff) +
                        matrix[3] * ((int)data[x]&0xff) +
                        matrix[4] * ((int)data[x]&0xff) +
                        matrix[5] * ((int)data[x]&0xff) +
                        matrix[6] * ((int)data[nextRow+1]&0xff) +
                        matrix[7] * ((int)data[nextRow+mask]&0xff) +
                        matrix[8] * ((int)data[nextRow+1]&0xff);
                color=(int)((float) color / (float) divisor);
                color = color>255? 255 : color<0 ? 0 : color;
// asigna el mismo color en RGB y valor alfa a 1
                aux[x] = 0xff000000 | (color<<16) | (color<<8) | color;
            }
        }

        return aux;
    }

    /*public static void convertYUV420_NV21toMirror(byte [] data, int [] pixels, int width, int height) {



    }*/


    public static int[] convertYUV420_NV21toMirror(byte[] input, int width, int height) {

        return convertYUV420_NV21toRGB8888(rotateYUV420Degree180(input,width,height),width,height);
        /*int x;
        for(int i=1;i < height - 1 ;i++) {
            for (int j = 1; j < width - 1; j++) {
                x = i * width + j;
                output[x] = input[x];
            }
        }*/
        //int[]aux = convertYUV420_NV21toRGB8888(input,output,width,height);
        /*output = metodo_prueba(input,output,width,height);
        for(int p:output){
            p = 0;
        }*/
        /*int j = input.length;
        byte[] aux = new byte[input.length];
        for (int i = 0; i < input.length; i++,j--) {
            aux[j - 1] = input[i];
        }
        input = aux;*/

    }


    private static byte[] rotateYUV420Degree180(byte[] data, int imageWidth, int imageHeight) {
        byte[] yuv = new byte[imageWidth * imageHeight * 3 / 2];
        int i = 0;
        int count = 0;
        for (i = imageWidth * imageHeight - 1; i >= 0; i--) {
            yuv[count] = data[i];
            count++;
        }
        i = imageWidth * imageHeight * 3 / 2 - 1;
        for (i = imageWidth * imageHeight * 3 / 2 - 1; i >= imageWidth
                * imageHeight; i -= 2) {
            yuv[count++] = data[i - 1];
            yuv[count++] = data[i];
        }
        return yuv;
    }

    private static byte[] mirrorYUV(byte[]data, int width, int height){

        byte[] mirror = new byte[data.length];
        int x;
        int cont = 0;
        for(int i=1;i < height - 1 ;i++) {
            for (int j = 1; j < width - 1; j++) {
                x = i * width + j;
                mirror[cont++] = data[x];
            }
        }
        /*int j = data.length;
        for (int i = 0; i < data.length; i++,j--) {
            mirror[j - 1] = data[i];
            //j = j - 1;
        }*/
        return mirror;
    }
}
