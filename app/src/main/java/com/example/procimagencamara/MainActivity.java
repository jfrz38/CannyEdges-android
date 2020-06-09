package com.example.procimagencamara;
import androidx.appcompat.app.AppCompatActivity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Camera;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.TextView;
import java.io.IOException;
public class MainActivity extends AppCompatActivity {
    Camera cam; // Cámara Android para capturar imagen.
    int camid = 0; // Identificador de la cámara Android.
    int rotation; // Ángulo de rotación a aplicar a los frames de la cámara.
    boolean preview;// True, si la cámara está funcionando.
    Support.MySurfaceHolderCallback mscb; // ¿Widget SurfaceView está preparado?
    MyPreviewCallback myPreviewCallback; // Ejecuta los algoritmos de proc.
    RadioButton[] actionRB; // Vector de RadioButtons para algoritmos de proc.
    int nActionRB = 5 ; // Número de RadioButtons.
    CheckBox[] optionCB; // Vector de CheckBoxes para diferentes opciones.
    int nOptionCB = 4; // Número de Checkboxes.
    private static final int CBPARALLEL = 0;
    private static final int CBNATIVE = 1;

    private static final int CBOMP = 2;
    private static final int CBNEON = 3;
    int[] procImage; // Buffer de la imagen de la cámara.
    int[] procImage2; // Buffer imagen procesada después de escalar y rotar.
    Bitmap resultBitmap; // Mapa de bits para mostrar en pantalla.
    long fpsT0, fpsT1; // Calcular FPS reales secuencia de frames procesadas.
    public int lastformat;
    public int lastwidth;
    public int lastheight;
    byte[]matrix; // Matrix para la convolución.
    int divisor; // Divisor para la convolución.
    CheckBox histogramCB;
    int[] histogram;
    int canvW, canvH;
    // Ejecución paralela de YUVto
    YUVtoParallel YUV2par; // Lista trabajo de hebras paralelas (executor).
    int nThreads; // Número de hebras.
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setRadioButtons(); // Activa todos los RadioButtons.
        setCheckBoxes(); // Activa todos los CheckBoxes.
        mscb = new Support.MySurfaceHolderCallback();
// Widget que muestra la imagen de la cámara.
        SurfaceView sv = (SurfaceView) findViewById(R.id.surfaceView);
        sv.getHolder().addCallback(mscb); // ¿widget funcionando?
// Otro widget que muestra la imagen procesada.
        SurfaceHolder surf2 = ((SurfaceView) findViewById(R.id.surfaceView2)).getHolder();
        Support.MySurfaceHolderCallback myshc2 = new Support.MySurfaceHolderCallback();
        surf2.addCallback(myshc2); // Detectar si el widget está funcionando.
// Crear el objeto para realizar el procesamiento de cada frame.
        myPreviewCallback = new MyPreviewCallback(surf2, myshc2);
        histogramCB = (CheckBox) this.findViewById(R.id.histogramCB);
// Inicializa la matriz convolution a unos valores por defecto
        matrix = new byte[]{-1,-1,-1,-1,8,-1,-1,-1,-1};
        divisor = 1;
    }
    @Override
    protected void onResume() {
        super.onResume();
        nThreads = Runtime.getRuntime().availableProcessors();
        YUV2par = new YUVtoParallel(nThreads); // Inicializa Java paralela.
    }
    @Override // Si se detiene la actividad, detener cámara y procesamiento.
    protected void onPause() {
        super.onPause();
        stopPreview(null);
        YUV2par.shutdown(); // Detiene la lista de hebras.
    }
    public void startPreview(View v) { // Si se pulsa el botón "Inicio"

        if (!preview && getCheckedActionRB() > 0) {
            preview = true;
            while (!mscb.surfaceready) {} // espera a que el widget esté listo.
            try {
                //Log.d("HOOK", "Entra en la cámara");
                cam = Camera.open(camid); // abre la cámara seleccionada.
            } catch (RuntimeException e) {
                preview = false;
                //Log.d("HOOK", "No puede acceder a la cámara "+camid);
                return;
            }
            SurfaceView sv = (SurfaceView) findViewById(R.id.surfaceView);
            try { // asocia el widget a la imagen de la cámara.
                cam.setPreviewDisplay(sv.getHolder());
            } catch (IOException e) {
                e.printStackTrace();
                preview = false;
                //Log.d("HOOK", "No puede acceder a la cámara " + camid);
                return;
            }
// calcula la rotación a aplicar a la imagen de la cámara.
            rotation = Support.setCameraDisplayOrientation(this, camid);
            cam.setDisplayOrientation(rotation); // aplica rotación a la imagen.
            Camera.Parameters parameters = cam.getParameters();
            lastformat = parameters.getPreviewFormat();
// escalar la imagen a las dimensiones del widget.
            lastwidth = parameters.getPreviewSize().width;
            lastheight = parameters.getPreviewSize().height;
            Camera.Size s = parameters.getPreviewSize();
// crear un buffer de la imagen.
            cam.addCallbackBuffer(new byte[3 * s.width * s.height / 2]);
// vincular el buffer a la imagen.
            cam.setPreviewCallbackWithBuffer(myPreviewCallback);
            myPreviewCallback.reset(); // resetear estadística del procesamiento.
            cam.startPreview(); // iniciar captura imagen.
            fpsT0 = System.nanoTime(); // guardar el tiempo de inicio.
        }
    }
    public void stopPreview(View v) { // Si se pulsa el botón "Detener"
        if (preview && cam != null) {
            fpsT1 = System.nanoTime(); // medir tiempo y calcular los FPS.
            //Log.d("HOOK", "FPS: "+(1000000000.0f/(double)(fpsT1-fpsT0))*(double)myPreviewCallback.count);

            cam.stopPreview(); // detener captura de imagen.
            cam.setPreviewCallback(null); // borrar buffer.
            cam.release(); // apagar la cámara.
// mostrar el tiempo medio de procesamiento del frame (FPS).
            TextView tv = (TextView) findViewById(R.id.mean);
            tv.setText("FPS (media): " + myPreviewCallback.getMean());
            myPreviewCallback.reset(); // resetear estadística del procesamiento.
        }
        preview = false;
    }
    // Realiza procesamiento a cada frame y lo muestra por el widget surfaceview.
    private final class MyPreviewCallback implements

            android.hardware.Camera.PreviewCallback {

        public long count=0;

        public long time=0;
        SurfaceHolder surf2 ;
        Support.MySurfaceHolderCallback myshc2;
        public proccesImageOnBackground myProccesImageOnBackground;
        MyPreviewCallback(SurfaceHolder _surf,Support.MySurfaceHolderCallback _mshcb) {
            surf2 = _surf;
            myshc2 = _mshcb;
        }
        public void reset() { // resetea la imagen procesada.
            if (myProccesImageOnBackground != null)
                myProccesImageOnBackground.cancel(true);
            count = 0;
            time = 0;
        }
        public float getMean() { // calcula el tiempo medio procesamiento (FPS).
            if (count == 0) return 0;
            return ((float)time/(float)count)/1000000.0f;
        }
        // Hebra captura frames de la cámara y almacena la imagen a procesar.
        public void onPreviewFrame (byte[] data, Camera camera) {
// Verifica que el format es YCrCb.
            if (lastformat == android.graphics.ImageFormat.NV21) {
                if (procImage == null)
// Vector para almacenar el frame de la imagen capturada.
                    procImage = new int[lastwidth * lastheight];

                myProccesImageOnBackground = (proccesImageOnBackground)new

                        proccesImageOnBackground(data, procImage, this).execute();

            }
        }
    }
    // Hebra (Tarea asíncrona) realiza el procesamiento de la imagen.
    private class proccesImageOnBackground extends AsyncTask<Void, Void, Void> {
        private byte[] data;
        public int[] procImage;
        MyPreviewCallback cb;
        proccesImageOnBackground (byte[] _data, int[] _procImage, MyPreviewCallback _cb) {
            data = _data;
            procImage = _procImage;
            cb = _cb;
        }
        @Override
        protected Void doInBackground (Void... voids) {
            //Log.d("HOOK", "Procesando frame de la imágen ...");
            long t0=0,t1=0;
            // Seleccionar el algoritmo de procesamiento.
            switch (getCheckedActionRB()) {
                case 1: // RGB
                    if (!isCheck(CBPARALLEL)) { // secuencial.
                        if (!isCheck(CBNATIVE)) { // Java (secuencial).
                            t0 = System.nanoTime();
                            System.out.println("img antes = "+procImage[0]);
                            procImage = YUVto.convertYUV420_NV21toRGB8888(data,lastwidth,lastheight);
                            System.out.println("img después = "+procImage[0]);
                            t1 = System.nanoTime();
                        }
                        else if(!isCheck(CBNEON)){ // Nativo (secuencial).
                            t0 = System.nanoTime();
                            YUVtoNative(YUVtoParallel.RGB,data,procImage,0,null,lastwidth,lastheight);
                            t1 = System.nanoTime();
                        }
                        else { // Native NEON (secuencial).
                            t0 = System.nanoTime();
                            YUVtoNativeNEON(YUVtoParallel.RGB,data,procImage,0,null,lastwidth,
                                    lastheight,1);
                            t1 = System.nanoTime();
                        }
                    } else { // Versión paralela.
                        if (!isCheck(CBNATIVE)) { // Java (paralela).
                            t0 = System.nanoTime();
                            YUV2par.convertYUV420_NV21to_parallel(YUVtoParallel.RGB,data,procImage,
                                    lastwidth,lastheight);
                            t1 = System.nanoTime();
                        }
                        else { // Nativo (paralelo).
                            if (!isCheck(CBOMP)) { // Nativo (paralelo) con pthread.
                                t0 = System.nanoTime();
                                YUVtoNativeParallel(YUVtoParallel.RGB,data,procImage,0,null,lastwidth,
                                        lastheight, nThreads);
                                t1 = System.nanoTime();
                            }
                            else if(!isCheck(CBNEON)){ // Nativo (paralelo) con OpenMP.
                                t0 = System.nanoTime();
                                YUVtoNativeParallelOMP(YUVtoParallel.RGB,data,procImage,0,null,
                                        lastwidth,lastheight, nThreads);
                                t1 = System.nanoTime();
                            }
                            else {
                                t0 = System.nanoTime(); // Nativo Neon(paralelo) con OpenMP.
                                YUVtoNativeNEON(YUVtoParallel.RGB,data,procImage,0,null,lastwidth,
                                        lastheight,nThreads);
                                t1 = System.nanoTime();
                            }
                        }
                    } break;

                case 2: // Escala de grises.
                    if (!isCheck(CBPARALLEL)) { // secuencial.
                        if (!isCheck(CBNATIVE)) { // Java (secuencial).
                            t0 = System.nanoTime();
                            procImage = YUVto.convertYUV420_NV21toGrey(data,lastwidth,lastheight);
                            t1 = System.nanoTime();
                        }
                        else if(!isCheck(CBNEON)) { // Nativo (secuencial).
                            t0 = System.nanoTime();
                            YUVtoNative(YUVtoParallel.GREYSCALE, data, procImage,0,null,
                                    lastwidth, lastheight);
                            t1 = System.nanoTime();
                        }
                        else { // Nativo NEON.
                            t0 = System.nanoTime();
                            YUVtoNativeNEON(YUVtoParallel.GREYSCALE,data,procImage,0,null,lastwidth,
                                    lastheight,1);
                            t1 = System.nanoTime();
                        }

                    } else { // Versión paralela.
                        if (!isCheck(CBNATIVE)) { // Java (paralelo).
                            t0 = System.nanoTime();
                            YUV2par.convertYUV420_NV21to_parallel(YUVtoParallel.GREYSCALE,data,
                                    procImage,lastwidth,lastheight);
                            t1 = System.nanoTime();
                        }
                        else { // Nativo (paralelo).
                            if (!isCheck(CBOMP)) { // Nativo (paralelo) con pthread.
                                t0 = System.nanoTime();
                                YUVtoNativeParallel(YUVtoParallel.GREYSCALE,data, procImage,0,null,
                                        lastwidth, lastheight, nThreads);
                                t1 = System.nanoTime();
                            }
                            else if(!isCheck(CBNEON)) { // Nativo (paralelo) con OpenMP.
                                t0 = System.nanoTime();
                                YUVtoNativeParallelOMP(YUVtoParallel.GREYSCALE,data,procImage,0,null,
                                        lastwidth, lastheight, nThreads);
                                t1 = System.nanoTime();
                            }
                            else { // Nativo (paralelo) con OpenMP NEON
                                t0 = System.nanoTime();
                                YUVtoNativeNEON(YUVtoParallel.GREYSCALE,data,procImage,0,null,
                                        lastwidth,lastheight, nThreads);
                                t1 = System.nanoTime();
                            }
                        }
                    } break;
                case 3: // Convolución.
                    if (!isCheck(CBPARALLEL)) { // Secuencial.

                        if (!isCheck(CBNATIVE)) { // Java (secuencial).
                            t0 = System.nanoTime();
                            procImage = YUVto.convertYUV420_NV21toConvolution(data,matrix,divisor,lastwidth,lastheight);
                            t1 = System.nanoTime();
                        }
                        else if(!isCheck(CBNEON)) { // Native (secuencial).
                            t0 = System.nanoTime();
                            YUVtoNative(YUVtoParallel.CONVOLUTION,data,procImage,divisor,matrix,
                                    lastwidth, lastheight);
                            t1 = System.nanoTime();
                        }
                        else { // Nativo NEON.
                            t0 = System.nanoTime();
                            YUVtoNativeNEON(YUVtoParallel.CONVOLUTION,data,procImage,divisor,
                                    matrix,lastwidth,lastheight,1);
                            t1 = System.nanoTime();
                        }
                    } else { // versión paralela.
                        if (!isCheck(CBNATIVE)) { // Java (paralelo).
                            t0 = System.nanoTime();
                            YUV2par.convertYUV420_NV21to_parallel(YUVtoParallel.CONVOLUTION,data,
                                    matrix,divisor,procImage,lastwidth,lastheight);
                            t1 = System.nanoTime();
                        }
                        else { // Nativo (paralelo).
                            if (!isCheck(CBOMP)) { // Nativo (paralelo) con pthread.
                                t0 = System.nanoTime();
                                YUVtoNativeParallel(YUVtoParallel.CONVOLUTION,data,procImage,divisor,
                                        matrix,lastwidth,lastheight,nThreads);
                                t1 = System.nanoTime();
                            }
                            else if(!isCheck(CBNEON)) { // Nativo (paralelo) con OpenMP.
                                t0 = System.nanoTime();
                                YUVtoNativeParallelOMP(YUVtoParallel.CONVOLUTION,data,procImage,
                                        divisor,matrix, lastwidth, lastheight, nThreads);
                                t1 = System.nanoTime();
                            }
                            else { // Nativo (paralelo) OpenMP NEON.
                                t0 = System.nanoTime();
                                YUVtoNativeNEON(YUVtoParallel.CONVOLUTION,data,procImage,divisor,
                                        matrix,lastwidth,lastheight,nThreads);
                                t1 = System.nanoTime();
                            }
                        }
                    } break;
                case 4: // Canny
                    if (!isCheck(CBPARALLEL)) { // Secuencial.
                        if (!isCheck(CBNATIVE)) { // Java (secuencial).
                            t0 = System.nanoTime();
                            procImage = YUVto.convertYUV420_NV21toCanny(data,lastwidth,lastheight);
                            t1 = System.nanoTime();
                        }else if(!isCheck(CBNEON)) { // Native (secuencial).
                            t0 = System.nanoTime();
                            //YUVtoNative(YUVtoParallel.CANNY,data,procImage,divisor,matrix,lastwidth, lastheight);
                            procImage = YuvToCannyNative(data,lastwidth, lastheight);
                            t1 = System.nanoTime();
                        }else { // Nativo NEON.
                            t0 = System.nanoTime();
                            YUVtoNativeNEON(YUVtoParallel.CANNY,data,procImage,divisor,matrix,lastwidth,lastheight,1);
                            t1 = System.nanoTime();
                        }
                    }else { // versión paralela.
                        if (!isCheck(CBNATIVE)) { // Java (paralelo).
                            t0 = System.nanoTime();
                            procImage = new CannyEdgesParallel(lastwidth, lastheight,data,nThreads).getMatriz_umbral();
                            t1 = System.nanoTime();
                        }
                        else { // Nativo (paralelo).
                            if (!isCheck(CBOMP)) { // Nativo (paralelo) con pthread.
                                t0 = System.nanoTime();
                                procImage = YuvToCannyNativeParallel(data,lastwidth, lastheight,nThreads);
                                t1 = System.nanoTime();
                            }
                            else if(!isCheck(CBNEON)) { // Nativo (paralelo) con OpenMP.
                                t0 = System.nanoTime();
                                //YUVtoNativeParallelOMP(YUVtoParallel.CANNY,data,procImage,divisor,matrix, lastwidth, lastheight, nThreads);
                                procImage = YuvToCannyNativeParallelOMP(data,lastwidth, lastheight,nThreads);
                                t1 = System.nanoTime();
                            }
                            else { // Nativo (paralelo) OpenMP NEON.
                                t0 = System.nanoTime();
                                YUVtoNativeNEON(YUVtoParallel.CANNY,data,procImage,divisor,
                                        matrix,lastwidth,lastheight,nThreads);
                                t1 = System.nanoTime();
                            }
                        }
                    } break;

                case 5: // Mirror
                    if (!isCheck(CBPARALLEL)) { // Secuencial.
                        if (!isCheck(CBNATIVE)) { // Java (secuencial).
                            t0 = System.nanoTime();
                            procImage = YUVto.convertYUV420_NV21toMirror(data,lastwidth,lastheight);
                            t1 = System.nanoTime();
                        }else if(!isCheck(CBNEON)) { // Native (secuencial).
                            t0 = System.nanoTime();
                            YUVtoNative(YUVtoParallel.MIRROR,data,procImage,divisor,matrix,lastwidth, lastheight);
                            t1 = System.nanoTime();
                        }else { // Nativo NEON.
                            t0 = System.nanoTime();
                            YUVtoNativeNEON(YUVtoParallel.MIRROR,data,procImage,divisor,matrix,lastwidth,lastheight,1);
                            t1 = System.nanoTime();
                        }
                    }else { // versión paralela.
                        if (!isCheck(CBNATIVE)) { // Java (paralelo).
                            t0 = System.nanoTime();
                            YUV2par.convertYUV420_NV21to_parallel(YUVtoParallel.MIRROR,data,
                                    matrix,divisor,procImage,lastwidth,lastheight);
                            t1 = System.nanoTime();
                        }
                        else { // Nativo (paralelo).
                            if (!isCheck(CBOMP)) { // Nativo (paralelo) con pthread.
                                t0 = System.nanoTime();
                                YUVtoNativeParallel(YUVtoParallel.MIRROR,data,procImage,divisor,
                                        matrix,lastwidth,lastheight,nThreads);
                                t1 = System.nanoTime();
                            }
                            else if(!isCheck(CBNEON)) { // Nativo (paralelo) con OpenMP.
                                t0 = System.nanoTime();
                                YUVtoNativeParallelOMP(YUVtoParallel.MIRROR,data,procImage,
                                        divisor,matrix, lastwidth, lastheight, nThreads);
                                t1 = System.nanoTime();
                            }
                            else { // Nativo (paralelo) OpenMP NEON.
                                t0 = System.nanoTime();
                                YUVtoNativeNEON(YUVtoParallel.MIRROR,data,procImage,divisor,
                                        matrix,lastwidth,lastheight,nThreads);
                                t1 = System.nanoTime();
                            }
                        }
                    } break;
            }
            cb.count ++; // incrementar número de frames procesados.
            cb.time += t1-t0; // sumar el tiempo consumido.
            return null;
        }
        @Override
        protected void onPostExecute(Void voids) {
            //Log.d("HOOK", "Creando imagen procesada ...");
            if ((cb.surf2!=null)&&(cb.myshc2.surfaceready)) {
                Canvas canv = cb.surf2.lockCanvas();
                if (canv != null) {
                    Paint p = new Paint();
                    canv.drawColor(android.graphics.Color.WHITE);

// obtener el tamaño para la nueva imagen RGB.
                    int canvW = canv.getWidth();
                    int canvH = canv.getHeight();
                    if (procImage2 == null) // crear vector para cada frame RGB procesada.
                        procImage2 = new int[canvH*canvW];
// desescalar y rotar imagen RGB.
                    Support.downscaleAndRotateImage(procImage,procImage2,lastwidth,lastheight,canvW,canvH,rotation);
// crear mapa de bit para cada frame procesado.
                    if (resultBitmap == null)
                        resultBitmap = Bitmap.createBitmap(canvW, canvH, android.graphics.Bitmap.Config.ARGB_8888);
// copiar la imagen RGB al mapa de bits.
                    resultBitmap.setPixels(procImage2,0,canvW,0,0,canvW,canvH);
// dibujar el mapa de bits.
                    canv.drawBitmap(resultBitmap,0,0,p);
                    p.setColor(android.graphics.Color.YELLOW);
                    p.setTextSize((float) 48.0);
                    p.setStyle(android.graphics.Paint.Style.FILL_AND_STROKE);
// mostrar el número de frames procesados.
                    canv.drawText(String.valueOf(cb.count), 10, 50, p);
                    if(histogramCB.isChecked()) { // Muestra el histograma.
                        if (histogram == null) histogram = new int[256];
                        histogram = Support.histogram(data,lastwidth,lastheight);
// Dibuja el histograma.
                        Paint paint = new Paint();
                        int h = canv.getHeight() - 10;
                        paint.setColor(Color.BLACK);
                        paint.setStrokeWidth(1);
                        canv.drawRect(10, h - 100, 270, h, paint);
// dibuja el histograma con líneas.
                        paint.setStyle(Paint.Style.FILL);
                        paint.setColor(Color.GREEN);
                        for (int i = 0; i < 256; i++) {
                            canv.drawLine(i+10,h,i+10,h-(histogram[i]),paint);
                        }
                    }
// Permite realizar una cola para mostrar frames en el widget.
                    cb.surf2.unlockCanvasAndPost(canv);
                }
            }
// devuelve el buffer para realizar el siguiente procesamiento del frame.
            cam.addCallbackBuffer(data);
        }
    }
    // Métodos NDK utilizando JNI.
    private native boolean isNEONSupported();
    public native void YUVtoNative(int tipo, byte[] data, int[] result,
                                   int divisor,byte[]matrix, int width, int height);
    public native void YUVtoNativeParallel(int tipo,byte[] data, int[] result,
                                           int divisor, byte[]matrix, int width, int height, int nthr);
    public native void YUVtoNativeParallelOMP(int tipo,byte[] data, int[] result,
                                              int divisor, byte[]matrix, int width, int height, int nthr);
    public native void YUVtoNativeNEON(int tipo, byte[] data, int[] result,
                                       int divisor,byte[]matrix, int width, int height, int nthreads);
    public native int[] YuvToCannyNative(byte[] data, int width, int height);

    public native int[] YuvToCannyNativeParallel(byte[] data, int width, int height, int nthreads);

    public native int[] YuvToCannyNativeParallelOMP(byte[] data, int width, int height, int nthreads);

    // Cargar librería nativa
    static {
        System.loadLibrary("processimg");
    }
    // Permite habilitar o deshabilitar los RadioButtons para seleccionar el algoritmo de procesamiento.
    private void setRadioButtons() {
// Permite que solo se seleccione un RadioButton.
        final View.OnClickListener actionRBlistener = new View.OnClickListener() {
            @Override

            public void onClick(View v) {
                for (int i=0; i<nActionRB; i++) {
                    if (actionRB[i].getId() != v.getId()) {
                        actionRB[i].setChecked(false);
                    }
                }
            }
        };
        actionRB = new RadioButton[nActionRB];
        int i = 0;
        RadioButton rb = (RadioButton) findViewById(R.id.rgb);
        rb.setEnabled(true); // habilita el radioboton RGB.
        rb.setOnClickListener(actionRBlistener); // Activa el listener.
        actionRB[i++] = rb; // almacena el identificador del RadioButton.
        rb = (RadioButton) findViewById(R.id.greyButton);
        rb.setEnabled(true);
        rb.setOnClickListener(actionRBlistener);
        actionRB[i++] = rb;
        rb = (RadioButton) findViewById(R.id.convolution);
        rb.setEnabled(true);
        rb.setOnClickListener(actionRBlistener);
        actionRB[i++] = rb;
        rb = (RadioButton) findViewById(R.id.canny);
        rb.setEnabled(true);
        rb.setOnClickListener(actionRBlistener);
        actionRB[i++] = rb;
        rb = (RadioButton) findViewById(R.id.mirror);
        rb.setEnabled(true);
        rb.setOnClickListener(actionRBlistener);
        actionRB[i++] = rb;
    }
    // Permite (des)habilitar Checkbox para las opciones de procesamiento.
    private void setCheckBoxes() {
// Checkbox 3 (OpenMP) puede ser seleccionado,
// si 1 (Nativo) y 2 (multihebras) han sido seleccionados.
        final View.OnClickListener actionCBlistener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (optionCB[CBPARALLEL].isChecked()&&optionCB[CBNATIVE].isChecked()) {
                    optionCB[CBOMP].setEnabled(true);
                } else {
                    optionCB[CBOMP].setEnabled(false);
                    optionCB[CBOMP].setChecked(false);
                }
                //TODO
                //if (isNEONSupported()) { // ¿soporta instrucciones NEON?
                if(false){
                    if (optionCB[CBNATIVE].isChecked()) {
                        optionCB[CBNEON].setEnabled(true);
                    } else {
                        optionCB[CBNEON].setEnabled(false);
                        optionCB[CBNEON].setChecked(false);
                    }
                    if (optionCB[CBPARALLEL].isChecked()&&optionCB[CBNEON].isChecked()) {
                        optionCB[CBOMP].setEnabled(true);
                        optionCB[CBOMP].setChecked(true);
                    }
                }
            }
        };
        optionCB = new CheckBox[nOptionCB];
        CheckBox cb = (CheckBox) findViewById(R.id.parallel);
        cb.setEnabled(true); // Habilita el checkbox.
        cb.setOnClickListener(actionCBlistener); // activa el listener.
        optionCB[CBPARALLEL] = cb; // almacena la referencia al CheckBox.
        cb = (CheckBox) findViewById(R.id.natv);

        cb.setEnabled(true);
        cb.setOnClickListener(actionCBlistener);
        optionCB[CBNATIVE] = cb;
        cb = (CheckBox) findViewById(R.id.omp);
        cb.setEnabled(false);
        cb.setOnClickListener(actionCBlistener);
        optionCB[CBOMP] = cb;
        cb = (CheckBox) findViewById(R.id.neon);
        cb.setEnabled(false);
        cb.setOnClickListener(actionCBlistener);
        optionCB[CBNEON] = cb;
    }
    private int getCheckedActionRB() { // Obtiene id RadioButton seleccionado.
        for (int i=0; i<nActionRB; i++) {
            if (actionRB[i].isChecked())
                return i+1;
        }
        return 0;
    }
    private boolean isCheck(int nCheckBox) {
        if (nCheckBox >= 0 && nCheckBox < nOptionCB)
            return optionCB[nCheckBox].isChecked();
        return false;
    }


}
