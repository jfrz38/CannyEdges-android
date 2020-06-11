package com.example.procimagencamara;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

public class MirrorParallel {

    int[] image;
    int width;
    int height;
    int size;
    int nThreads;
    byte[] original_image;
    byte[] rotate_image;

    public MirrorParallel(int width, int height, byte[] data, int nThreads){
        this.size = width*height;
        this.width = width;
        this.height = height;
        this.nThreads = nThreads;
        this.original_image = data;
        this.rotate_image = new byte[size * 3 / 2];
        this.image = new int[size];

        List<Callable<Void>> todoTasks = new ArrayList<>(nThreads);
        for (int i=0; i < nThreads; i++) {
            todoTasks.add(i, new MirrorParallel.myJavaWorker(i));
        }
        try {
            Executors.newFixedThreadPool(nThreads).invokeAll(todoTasks);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void mirror(int id){
        rotate_image(id);
        image_toRGB(id);
    }

    private void image_toRGB(int id){
        int offset = size;
        int u, v, y1, y2, y3, y4;
        int init = (int)(((float)id/(float)nThreads)*((float)height/2.0f));
        int finish = (int)(((float)(id+1)/(float)nThreads)*((float)height/2.0f));
        int myInit = 2*width*init;
        int myFinish = 2*width*finish;
        int myUVOff = offset+init*width;
        for(int i=myInit, k=myUVOff; i < myFinish; i+=2, k+=2) {

// cada region 4x4 en el mapa de bits tiene el mismo (u,v) pero diferentes y1,y2,y3,y4
            y1 = rotate_image[i ]&0xff;
            y2 = rotate_image[i+1]&0xff;
            y3 = rotate_image[width+i ]&0xff;
            y4 = rotate_image[width+i+1]&0xff;
            v = rotate_image[k ]&0xff;
            u = rotate_image[k+1]&0xff;
            u = u-128;
            v = v-128;
            image[i ] = convertYUVtoRGB(y1, u, v);
            image[i+1] = convertYUVtoRGB(y2, u, v);
            image[width+i ] = convertYUVtoRGB(y3, u, v);
            image[width+i+1] = convertYUVtoRGB(y4, u, v);
            if (i!=0 && (i+2)%width==0) i+=width;
        }
    }

    private void rotate_image(int id){

        int myInit = size/nThreads*id;
        int myFinish = size/nThreads*(id+1);

        System.out.println("Hilo "+id+" v1: My init = "+myInit+" ; myFinish = "+myFinish+" ; size * 3 / 2 = "+(size * 3 / 2)+" size(w*h) = = "+(width*height));
        int i;
        int count = myInit;
        //(i = imageWidth * imageHeight - 1; i >= 0; i--)
        for (i = myFinish - 1; i >= myInit; i--) {
            rotate_image[count] = original_image[i];
            count++;
        }

        int newSize = (size * 3 / 2) - size;
        myInit = newSize/nThreads*id;
        myFinish = newSize/nThreads*(id+1);
        myInit+=size;
        myFinish+=size;
        //myInit+=size;
        //myFinish = (size * 3 / 2)/nThreads*(id+1);
        System.out.println("Hilo "+id+" v2: My init = "+myInit+" ; myFinish = "+myFinish+" ; size * 3 / 2 = "+(size * 3 / 2)+" size(w*h) = = "+(width*height) + " ; diferencia = "+(myFinish-myInit));
        //(i = imageWidth * imageHeight * 3 / 2 - 1; i >= imageWidth*imageHeight; i -= 2)
        for (i = myFinish - 1; i >= myInit; i -= 2) {
            rotate_image[count++] = original_image[i - 1];
            rotate_image[count++] = original_image[i];
        }
    }

    private int convertYUVtoRGB(int y, int u, int v) {
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

    public int[] getImage(){
        return image;
    }

    private final class myJavaWorker implements Callable<Void> {
        private int id;

        public myJavaWorker(int _id) {
            id = _id;
        }

        public Void call(){
            mirror(id);
            return null;
        }
    }
}
