#include <jni.h>
#include <string>
#include <android/log.h>
#include <omp.h>
#define MAX_NUM_THREADS 16
typedef struct paramST { // Estructura de datos para asignar trabajo a cada hebra.
    const unsigned char * data;
    int * pixels;
    int width;
    int height;
    int my_id;
    int nthr;
    int * matrix;
    int divisor;
} paramST;

#if defined(__ARM_ARCH_7A__) && defined(__ARM_NEON__)
/*
    #include <arm_neon.h>
    #define bytes_per_pixel 4 // Valores de alga + RGB.
    static int convertYUV420_NV21toRGB8888_NEON(const unsigned char * data, int * pixels, int width, int height,int ompFlag) {

        unsigned char* out = (unsigned char*) pixels;
        unsigned char const* yuv= data;
        unsigned char const fill_alpha=0xff;
        // pre-condición: Multiplicar por 8 width y height debe ser par.
        if (0!=(width&7) || width<8 || 0!=(height&1) || height<2 || !out || !yuv)
        return 0;
        // punteros de Y' y UV.
        unsigned char const* y = yuv;
        unsigned char const* uv = yuv + (width*height);
        // contar iteraciones.
        int const itHeight = height>>1;
        int const itWidth = width>>3;
        // para recorrer cada linea de la imagen.
        int const stride = width*bytes_per_pixel;

        // numéro total de bytes para cada escritura.
        int const dst_pblock_stride = 8*bytes_per_pixel;
        // cada blocke almacena temporalmente 8 pixeles consecutivos.
        uint8x8x4_t pblock; // Array de 4 vectores con 8 lineas, y 8 bits cada una.
        // Última posición del array inicializado con 8 líneas igual a 0xFF.
        pblock.val[3] = vdup_n_u8(fill_alpha); // alpha channel in the last
        // Operación a realizar:
        //R = [128 + 298(Y - 16) + 409(V - 128)] >> 8
        //G = [128 + 298(Y - 16) - 100(V - 128) - 208(U - 128)] >> 8
        //B = [128 + 298(Y - 16) + 516(U - 128)] >> 8
        uint8x8_t const Yshift = vdup_n_u8(16); // Constante para restar a y.
        int16x8_t const half = vdupq_n_s16(128); // Constante par restar a u y v.
        // Constante para redondear, sumando 128/256=0.5.
        int32x4_t const rounding = vdupq_n_s32(128);
        uint16x8_t t; // variable temporal para almacenar y, uv.
        int i,j;
        if(ompFlag){ // versión paralela con OMP & NEON
        #pragma omp parallel for schedule(guided) private(i,t,y,uv,out)

        shared(yuv,pixels,width,height) firstprivate(pblock)

        for ( j=0; j<itHeight; ++j) {
        // y incrementa cada 2 * 640 (2 * width) bytes en cada iteración de j.
        y=yuv+j*2*width;
        uv=yuv+(width*height)+j*width; // uv incrementa 640B (width) por j.
        // en pixeles (4B) cada iteración de j incrementa 2*width pixels.
        out=(unsigned char*)pixels+j*2*width*bytes_per_pixel;
        for ( i=0; i<itWidth; ++i, y+=8, uv+=8, out+=dst_pblock_stride) {
        // cargar valores u8x8 y, restar 16 a cada uno y convertir a u16x8.
        t = vmovl_u8(vqsub_u8(vld1_u8(y), Yshift));
        // dividir u16x8 en dos partes de u16x4 que son multiplicados
        // por 298 y convertir a u32x4.
        int32x4_t const Y00 = vmulq_n_u32(vmovl_u16(vget_low_u16(t)), 298);
        int32x4_t const Y01 = vmulq_n_u32(vmovl_u16(vget_high_u16(t)), 298);
        // repetir para la siguiente fila que comparte los valores de u y v.
        t = vmovl_u8(vqsub_u8(vld1_u8(y+width), Yshift));
        int32x4_t const Y10 = vmulq_n_u32(vmovl_u16(vget_low_u16(t)), 298);
        int32x4_t const Y11 = vmulq_n_u32(vmovl_u16(vget_high_u16(t)), 298);
        // cargar un bloque de 4 uv en uint8x8_t: {u0,v0, u1,v1, u2,v2, u3,v3}
        // bloque u8x8 se convierte a u16x8 y se resta 128 en cada línea.
        t = vsubq_s16((int16x8_t)vmovl_u8(vld1_u8(uv)), half);
        // Desempaquetar la operación para calcular el array UV.
        // Parte baja de t Parte alta de t
        // u2 v2 u3 v3 u0 v0 u1 v1
        // Quedando de la siguiente forma:
        // UV.val[0]: u0, u1, u2, u3
        // UV.val[1]: v0, v1, v2, v3
        int16x4x2_t const UV = vuzp_s16(vget_low_s16(t), vget_high_s16(t));
        // tR: 128+409V
        int32x4_t const tR = vmlal_n_s16(rounding, UV.val[1], 409);
        int32x4_t const tG = vmlal_n_s16(vmlal_n_s16(rounding, UV.val[0],
        -100), UV.val[1], -208); // tG: 128-100U-208V

        // tB: 128+516U
        int32x4_t const tB = vmlal_n_s16(rounding, UV.val[0], 516);
        // combinar TR con dos diferentes y.
        // [tR0,tR0,tR1,tR1][tR2,tR2,tR3,tR3]
        int32x4x2_t const R=vzipq_s32(tR,tR);
        // [tG0, tG0, tG1, tG1] [tG2, tG2, tG3, tG3]

        int32x4x2_t const G = vzipq_s32(tG, tG);
        // [tB0, tB0, tB1, tB1] [tB2, tB2, tB3, tB3]
        int32x4x2_t const B = vzipq_s32(tB, tB);
        // Las siguientes funciones utilizadas:
        // vaddq_s32 sumar, utilizada en: int32x4_t
        // vaddq_s32(int32x4_t a, int32x4_t b); VADD.I32 q0,q0,q0
        // vqmovun_s32 Vector desborado entero pasando de signo a sin signo.
        // uint16x4_t vqmovun_s32(int32x4_t a); VQMOVUN.S32 d0,q0
        // Unir dos vectores de 64 bit en un de 128 bit.
        // uint16x8_t vcombine_u16(uint16x4_t low, uint16x4_t high);
        // VMOV d0,d0
        // Desplazamiento a la derecha de un vector por una constante.
        // uint8x8_t vshrn_n_u16(uint16x8_t a, __constrange(1,8) int b);
        // VSHRN.I16 d0,q0,#8
        // Realizado sobre 8 pixeles superiores.
        pblock.val[0]= vshrn_n_u16(vcombine_u16(vqmovun_s32(vaddq_s32(
        R.val[0],Y00)), vqmovun_s32(vaddq_s32(R.val[1], Y01))), 8);
        pblock.val[1] = vshrn_n_u16(vcombine_u16(vqmovun_s32(vaddq_s32(
        G.val[0], Y00)), vqmovun_s32(vaddq_s32(G.val[1], Y01))), 8);
        pblock.val[2] = vshrn_n_u16(vcombine_u16(vqmovun_s32(vaddq_s32(
        B.val[0], Y00)), vqmovun_s32(vaddq_s32(B.val[1], Y01))), 8);
        // Trabajar con bloques de 4 elementos: pblock= a1 a2 a3 a4 a5 a6 a7
        // a8 b1 b2 b3 b4 b5 b6 b7 b8 g1 g2 g3 g4 g5 g6 g7 g8 r1 r2 r3 r4 r5
        // obteniendo vst4= a1 b1 g1 r1 a2 b2 g2 r2 ....
        vst4_u8(out, pblock);
        // Para la siguiente fila (+ width) usando los mismos valores (u, v).
        // Realizado sobre 8 pixeles inferiores.
        pblock.val[0] =vshrn_n_u16(vcombine_u16(vqmovun_s32(vaddq_s32(
        R.val[0], Y10)), vqmovun_s32(vaddq_s32(R.val[1], Y11))), 8);
        pblock.val[1] =vshrn_n_u16(vcombine_u16(vqmovun_s32(vaddq_s32(
        G.val[0], Y10)), vqmovun_s32(vaddq_s32(G.val[1], Y11))), 8);
        pblock.val[2] =vshrn_n_u16(vcombine_u16(vqmovun_s32(vaddq_s32(
        B.val[0], Y10)), vqmovun_s32(vaddq_s32(B.val[1], Y11))), 8);

        vst4_u8(out+stride, pblock);
        }
        }
        }
        else {
        for ( j=0; j<itHeight; ++j, y+=width, out+=stride) {
        for ( i=0; i<itWidth; ++i, y+=8, uv+=8, out+=dst_pblock_stride) {
        // cargar valores u8x8 de y, restar 16 y convertir a u16x8.
        t = vmovl_u8(vqsub_u8(vld1_u8(y), Yshift));
        // desglosar u16x8 en dos u16x4 que son multiplicados por 298 y
        // convertidos en u32x4.
        int32x4_t const Y00 = vmulq_n_u32(vmovl_u16(vget_low_u16(t)), 298);
        int32x4_t const Y01 = vmulq_n_u32(vmovl_u16(vget_high_u16(t)), 298);
        // repetir con la fila siguiente para los valores u y v.
        t = vmovl_u8(vqsub_u8(vld1_u8(y+width), Yshift));
        int32x4_t const Y10 = vmulq_n_u32(vmovl_u16(vget_low_u16(t)), 298);
        int32x4_t const Y11 = vmulq_n_u32(vmovl_u16(vget_high_u16(t)), 298);
        // cargar 4 bloques de uv en un uint8x8_t {u0,v0, u1,v1, u2,v2, u3,v3}
        // pasar de u8x8 a u16x8 y restar 128 a cada línea.
        t = vsubq_s16((int16x8_t)vmovl_u8(vld1_u8(uv)), half);
        // Operación para extraer y calcular el array UV
        // Parte baja de t Parte alta de t
        // u2 v2 u3 v3 u0 v0 u1 v1
        // Operando queda:
        // UV.val[0]: u0, u1, u2, u3

        // UV.val[1]: v0, v1, v2, v3
        int16x4x2_t const UV = vuzp_s16(vget_low_s16(t), vget_high_s16(t));
        // tR: 128+409V
        int32x4_t const tR=vmlal_n_s16(rounding,UV.val[1],409);
        int32x4_t const tG = vmlal_n_s16(vmlal_n_s16(rounding, UV.val[0],
        -100), UV.val[1], -208); // tG: 128-100U-208V

        // tB: 128+516U
        int32x4_t const tB = vmlal_n_s16(rounding, UV.val[0], 516);
        // Combinar TR con dos diferentes y.
        // [tR0, tR0, tR1, tR1] [tR2, tR2, tR3, tR3]
        int32x4x2_t const R = vzipq_s32(tR, tR);
        // [tG0, tG0, tG1, tG1] [tG2, tG2, tG3, tG3]
        int32x4x2_t const G = vzipq_s32(tG, tG);
        // [tB0, tB0, tB1, tB1] [tB2, tB2, tB3, tB3]
        int32x4x2_t const B = vzipq_s32(tB, tB);
        // Operaciones utilizadas:
        // vaddq_s32 suma: int32x4_t vaddq_s32(int32x4_t a, int32x4_t b);
        // VADD.I32 q0,q0,q0
        // vqmovun_s32 Vector entero desbordado de signo a sin signo.
        // uint16x4_t vqmovun_s32(int32x4_t a); VQMOVUN.S32 d0,q0
        // Une dos vectores de 64 bit en un vector de 128 bit.
        // uint16x8_t vcombine_u16(uint16x4_t low, uint16x4_t high);
        // VMOV d0,d0
        // Vector desplazamiento a la derecha por una constante
        // uint8x8_t vshrn_n_u16(uint16x8_t a, __constrange(1,8) int b);
        // VSHRN.I16 d0,q0,#8
        // Sobre los 8 píxeles más significativos.
        pblock.val[0] = vshrn_n_u16(vcombine_u16(vqmovun_s32(vaddq_s32(
        R.val[0], Y00)), vqmovun_s32(vaddq_s32(R.val[1], Y01))), 8);
        pblock.val[1] = vshrn_n_u16(vcombine_u16(vqmovun_s32(vaddq_s32(
        G.val[0], Y00)), vqmovun_s32(vaddq_s32(G.val[1], Y01))), 8);
        pblock.val[2] = vshrn_n_u16(vcombine_u16(vqmovun_s32(vaddq_s32(
        B.val[0], Y00)), vqmovun_s32(vaddq_s32(B.val[1], Y01))), 8);
        // Trabajar con bloques de 4 elementos: pblock= a1 a2 a3 a4 a5 a6 a7
        // a8 b1 b2 b3 b4 b5 b6 b7 b8 g1 g2 g3 g4 g5 g6 g7 g8 r1 r2 r3 r4 r5 r6
        // obteniendo vst4= a1 b1 g1 r1 a2 b2 g2 r2 ....
        vst4_u8(out, pblock);
        // Para la siguiente fila (+ width) usando los mismos valores (u, v).
        // Realizado sobre 8 pixeles inferiores.
        pblock.val[0] =vshrn_n_u16(vcombine_u16(vqmovun_s32(vaddq_s32(
        R.val[0], Y10)), vqmovun_s32(vaddq_s32(R.val[1], Y11))), 8);
        pblock.val[1] =vshrn_n_u16(vcombine_u16(vqmovun_s32(vaddq_s32(
        G.val[0], Y10)), vqmovun_s32(vaddq_s32(G.val[1], Y11))), 8);
        pblock.val[2] =vshrn_n_u16(vcombine_u16(vqmovun_s32(vaddq_s32(
        B.val[0], Y10)), vqmovun_s32(vaddq_s32(B.val[1], Y11))), 8);

        vst4_u8(out+stride, pblock);
        }
        }
        }
        return 1;
    }

    static int convertYUV420_NV21toGreyScale_NEON(const unsigned char* data, int* pixels,
    int width, int height,int ompFlag) {

        unsigned char* out = (unsigned char*) pixels;
        unsigned char const* yuv= data;
        unsigned char const fill_alpha=0xff;

        // pre-condición: width debe ser múltiplo de 8, height debe ser par.
        if (0!=(width&7)||width<8||0!=(height&1)||height<2||!out||!yuv) return 0;
        // punteros Y' y UV
        unsigned char const* y = yuv;
        // contar iteraciones.
        int const itWidth = width>>3;
        int const itSize = itWidth*height;
        // número total de bytes de cada escritura.
        int const dst_pblock_stride = 8*bytes_per_pixel;
        // Un bloque trabaja con 8 pixeles consecutivamente.
        uint8x8x4_t pblock; // Array de 4 vectores con 8 líneas, cada uno de 8 bits.
        // Última posición del array inicializado con 8 líneas igual a 0xFF.
        pblock.val[3] = vdup_n_u8(fill_alpha); // el valor alpha es el último
        uint8x8_t t; // variable temporal para cargar y
        int i,j;
        if(ompFlag){ // versión paralela con OMP & NEON
        #pragma omp parallel for schedule(guided) private(t) shared(y,out)

        firstprivate(pblock)

        for ( i=0; i<itSize; i++) {
        // Carga un bloque de luminancias
        t = vld1_u8((uint8_t const *)y + i*8);
        // Guarda en pblock los valores de luminancia de p en las tres
        // posiciones (RGB)
        pblock.val[0] = t;
        pblock.val[1] = t;
        pblock.val[2] = t;
        // Escribe pblock en la salida + dst_pblock_stride*i
        vst4_u8((uint8_t *)out + i*dst_pblock_stride, pblock);
        }
        } else { // versión con intrínsecas NEON
        for ( i=0; i<itSize; ++i, y+=8, out+=dst_pblock_stride) {
        // Carga un bloque de luminancias
        t = vld1_u8(y);
        // Guarda en pblock los valores de luminancia de p en las tres posiciones
        // (RGB)
        pblock.val[0] = t;
        pblock.val[1] = t;
        pblock.val[2] = t;
        // Escribe pblock en la salida
        vst4_u8(out, pblock);
        }
        }
        return 1;
    }
    // Precalcula los kernels desplazados y almacena los Y promocionados a 16 bits en vez
    // de hacerlo cada vez se procesan las últimas columnas (resto de dividir por 6)
    static int convertYUV420_Convolution_NEON(unsigned char* data, int* pixels, int width,
    int height, char *kernel, char div, int ompFlag) {

        unsigned char* outini = (unsigned char*) pixels;
        unsigned char const* yuv= data;
        unsigned char const fill_alpha=0xff;
        // pre-condición: width debe ser múltiplo de 8, height debe ser par
        if (0!=(width&7)||width<8||0!=(height&1)||height<2||!outini||!yuv) return 0;
        // se van a calcular 6 píxel cada vez
        // introducimos en una estructura de 3 registros (s16), dos veces el kernel de la
        // siguiente forma:
        // lsb msb

        // korg.val[0] = k0 k1 k2 k0 k1 k2 0 0
        // korg.val[1] = k3 k4 k5 k3 k4 k5 0 0
        // korg.val[2] = k6 k7 k8 k6 k7 k8 0 0
        // también creamos versiones desplazadas del kernel para procesar distintos bits
        // de la imagen
        // k1.val[0] = 0 k0 k1 k2 k0 k1 k2 0
        // k1.val[1] = 0 k3 k4 k5 k3 k4 k5 0
        // k1.val[2] = 0 k6 k7 k8 k6 k7 k8 0
        // k2.val[0] = 0 0 k0 k1 k2 k0 k1 k2
        // k2.val[1] = 0 0 k3 k4 k5 k3 k4 k5
        // k2.val[2] = 0 0 k6 k7 k8 k6 k7 k8
        int16x8x3_t korg;
        int16x8x3_t k1, k2;
        // creamos un reg de 64 con la primera fila de los kernels (k0,k1,k2)
        uint64_t a = (uint64_t)kernel[0] | (uint64_t)kernel[1] << 8 | (uint64_t)kernel[2]
        << 16 | (uint64_t)kernel[0] << 24 | (uint64_t)kernel[1] << 32 |
        (uint64_t)kernel[2] << 40;
        // el registro de 64 lo interpretamos como int8x8 y lo promocionamos a int16x8
        korg.val[0] = vmovl_s8(vcreate_s8(a));
        // el mismo registro lo desplazamos 8 bits y 16 bits para crear las versiones 8x8
        // y luego promocionamos a 16x8 de la primera fila de los kernels desplazados
        k1.val[0] = vmovl_s8(vreinterpret_s8_s64(vshl_n_s64(vcreate_s64(a),8)));
        k2.val[0] = vmovl_s8(vreinterpret_s8_s64(vshl_n_s64(vcreate_s64(a),16)));
        // repetimos la operación para la 2a y 3a fila del kernel (k3,k4,k5 y k6,k7,k8)
        a = (uint64_t)kernel[3] | (uint64_t)kernel[4] << 8 | (uint64_t)kernel[5] << 16 |
        (uint64_t)kernel[3] << 24 | (uint64_t)kernel[4] << 32 | (uint64_t)kernel[5] << 40;
        korg.val[1] = vmovl_s8(vcreate_s8(a));
        k1.val[1] = vmovl_s8(vreinterpret_s8_s64(vshl_n_s64(vcreate_s64(a),8)));
        k2.val[1] = vmovl_s8(vreinterpret_s8_s64(vshl_n_s64(vcreate_s64(a),16)));
        a = (uint64_t)kernel[6] | (uint64_t)kernel[7] << 8 | (uint64_t)kernel[8] << 16 |
        (uint64_t)kernel[6] << 24 | (uint64_t)kernel[7] << 32 | (uint64_t)kernel[8] << 40;
        korg.val[2] = vmovl_s8(vcreate_s8(a));
        k1.val[2] = vmovl_s8(vreinterpret_s8_s64(vshl_n_s64(vcreate_s64(a),8)));
        k2.val[2] = vmovl_s8(vreinterpret_s8_s64(vshl_n_s64(vcreate_s64(a),16)));
        // punteros Y' and UV.
        unsigned char const* y = yuv;
        // contar iteracciones
        int const itHeight = height-2; // todas las filas menos la primera y la última.
        int const itWidth = (width-2)/6; // vamos a generar 6 pixels cada paso.
        int const itWidthPad = width-2 - itWidth*6;// resto de fila que no se va procesar.
        // incremento de cada línea.
        int const stride = width*bytes_per_pixel;
        // número total de bytes de cada escritura.
        int const dst_pblock_stride = 6*bytes_per_pixel; //avanza de 6 en 6 pixels salida
        // el primer pixel a escribir es el segundo (+1) de la segunda fila (+width)
        outini += (width+1)*bytes_per_pixel;
        unsigned char* out = outini;
        // un bloque almacena 8 pixeles consecutivamente.
        uint8x8x4_t pblock; // Array de 4 vectores con 8 líneas con 8 bits cada una.
        // La última posición del array inicializado con 8 líneas igual a 0xFF.
        pblock.val[3] = vdup_n_u8(fill_alpha); // alpha es el último valor.
        int16x8_t res = vdupq_n_s16(0);
        // variables temporales para cargar y, uv.
        int i, j;
        int width2 = width<<1;
        if (ompFlag) {
        #pragma omp parallel for schedule(guided) private(y,out,j)
        shared(yuv,outini,korg,k1,k2) firstprivate(pblock, res, itWidthPad)

        for ( i = 0; i < itHeight; i++) {
        y = yuv + i*width;
        out = outini + i*width*bytes_per_pixel;
        // bucle que recorre cada fila (itHeight)
        for (j = 0; j < itWidth; j++, y += 6, out += dst_pblock_stride) {
        // los pixeles de la fila (itWidth) se procesan de 6 en 6
        // leemos 8 pixeles de Y de la fila anterior (y), la actual (out)
        // y la siguiente y promocionamos a uint16x8
        // yy.val[0] = y00 y01 y02 y03 y04 y05 y06 y07
        // yy.val[1] = y10 y11 y12 y13 y14 y15 y16 y17
        // yy.val[2] = y20 y21 y22 y23 y24 y25 y26 y27
        uint16x8x3_t yy;
        yy.val[0] = vmovl_u8(vld1_u8(y));
        yy.val[1] = vmovl_u8(vld1_u8(y + width));
        yy.val[2] = vmovl_u8(vld1_u8(y + width2));
        int16x8_t m;
        // multiplicamos yy.val[0] por korg.val[0] (como s16)
        // m = k0*y00 k1*y01 k2*y02 k0*y03 k1*y04 k2*y05 0*y06 0*y07
        m = vmulq_s16(yy.val[0], korg.val[0]);
        // ahora hacemos lo mismo con yy.val[1]y korg.val[1] y se lo
        // sumamos a lo anterior (m)
        // m = k0*y00+k3*y10 k1*y01+k4*y11 k2*y02+k5*y12 k0*y03+k3*y13
        // k1*y04+k4*y14 k2*y05+k5*y15 0 0
        m = vmlaq_s16(m, yy.val[1], korg.val[1]);
        // ahora hacemos lo mismo con yy.val[2]y korg.val[2] y se lo
        // sumamos a lo anterior (m)
        // m = k0*y00+k3*y10+k6*y20 k1*y01+k4*y11+k7*y21
        // k2*y02+k5*y12+k8*y22 k0*y03+k3*y13+k6*y23 k1*y04+k4*y14+k7*y24
        // k2*y05+k5*y15+k8*y25 0 0
        m = vmlaq_s16(m, yy.val[2], korg.val[2]);
        // La convolución para y11 (posición 0 de la salida out)
        // se obtiene sumando los tres primeros términos de m (0,1,2)
        // res[0] = k0*y00+k3*y10+k6*y20+k1*y01+k4*y11+k7*y21+k2*y02+
        // k5*y12+k8*y22
        res = vsetq_lane_s16((vgetq_lane_s16(m, 0) + vgetq_lane_s16(m, 1)
        + vgetq_lane_s16(m, 2)) / div,res, 0);
        // La convolución para y14 (posición 3 de la salida out) se
        // obtiene sumando los tres segundos términos de m (3,4,5)
        // res[3] = k0*y03+k3*y13+k6*y23+k1*y04+k4*y14+k7*y24+k2*y05+
        // k5*y15+k8*y25
        res = vsetq_lane_s16((vgetq_lane_s16(m, 3) + vgetq_lane_s16(m, 4)
        + vgetq_lane_s16(m, 5)) / div,res, 3);
        // Repetimos todas las operaciones con k1, con lo que obtenemos
        // convolución para y12 e y15
        m = vmulq_s16(yy.val[0], k1.val[0]);
        m = vmlaq_s16(m, yy.val[1], k1.val[1]);
        m = vmlaq_s16(m, yy.val[2], k1.val[2]);
        res = vsetq_lane_s16((vgetq_lane_s16(m, 1) + vgetq_lane_s16(m, 2)
        + vgetq_lane_s16(m, 3)) / div,res, 1);
        res = vsetq_lane_s16((vgetq_lane_s16(m, 4) + vgetq_lane_s16(m, 5)
        + vgetq_lane_s16(m, 6)) / div,res, 4);
        // Repetimos todas las operaciones con k2, con lo que obtenemos
        // convolución para y13 e y16
        m = vmulq_s16(yy.val[0], k2.val[0]);
        m = vmlaq_s16(m, yy.val[1], k2.val[1]);
        m = vmlaq_s16(m, yy.val[2], k2.val[2]);
        res = vsetq_lane_s16((vgetq_lane_s16(m, 2) + vgetq_lane_s16(m, 3)
        + vgetq_lane_s16(m, 4)) / div,res, 2);

        res = vsetq_lane_s16((vgetq_lane_s16(m, 5) + vgetq_lane_s16(m, 6)
        + vgetq_lane_s16(m, 7)) / div,res, 5);
        // como salida es en RGB, replicamos el valor en los tres canales
        pblock.val[0] = vqmovun_s16(res);
        pblock.val[1] = pblock.val[0];
        pblock.val[2] = pblock.val[0];
        // lo escribimos en out entrelazado como hacíamos en YUV2RGB
        vst4_u8(out, pblock);
        }
        if (itWidthPad) {
        //Sobran pixeles al final de la fila, width-2 no es múltiplo de 6,
        // hacemos un paso adicional al final con los 6
        // últimos pixeles de cada fila, aunque se repita alguno
        // últimos 6 pixeles de la fila
        y = y-6+itWidthPad;
        out = out-(6-itWidthPad)*bytes_per_pixel;
        uint16x8x3_t yy;
        yy.val[0] = vmovl_u8(vld1_u8(y));
        yy.val[1] = vmovl_u8(vld1_u8(y + width));
        yy.val[2] = vmovl_u8(vld1_u8(y + width2));
        int16x8_t m;
        m = vmulq_s16(yy.val[0], korg.val[0]);
        m = vmlaq_s16(m, yy.val[1], korg.val[1]);
        m = vmlaq_s16(m, yy.val[2], korg.val[2]);
        res = vsetq_lane_s16((vgetq_lane_s16(m, 0) + vgetq_lane_s16(m, 1)
        + vgetq_lane_s16(m, 2))/div, res, 0);
        res = vsetq_lane_s16((vgetq_lane_s16(m, 3) + vgetq_lane_s16(m, 4)
        + vgetq_lane_s16(m, 5))/div, res, 3);

        m = vmulq_s16(yy.val[0], k1.val[0]);
        m = vmlaq_s16(m, yy.val[1], k1.val[1]);
        m = vmlaq_s16(m, yy.val[2], k1.val[2]);
        res = vsetq_lane_s16((vgetq_lane_s16(m, 1) + vgetq_lane_s16(m, 2)
        + vgetq_lane_s16(m, 3))/div, res, 1);
        res = vsetq_lane_s16((vgetq_lane_s16(m, 4) + vgetq_lane_s16(m, 5)
        + vgetq_lane_s16(m, 6))/div, res, 4);

        m = vmulq_s16(yy.val[0], k2.val[0]);
        m = vmlaq_s16(m, yy.val[1], k2.val[1]);
        m = vmlaq_s16(m, yy.val[2], k2.val[2]);
        res = vsetq_lane_s16((vgetq_lane_s16(m, 2) + vgetq_lane_s16(m, 3)
        + vgetq_lane_s16(m, 4))/div, res, 2);
        res = vsetq_lane_s16((vgetq_lane_s16(m, 5) + vgetq_lane_s16(m, 6)
        + vgetq_lane_s16(m, 7))/div, res, 5);

        pblock.val[0] = vqmovun_s16(res);
        pblock.val[1] = pblock.val[0];
        pblock.val[2] = pblock.val[0];
        vst4_u8(out, pblock);
        }
        }
        } else {
        // versión serie
        for (i = 0; i < itHeight; i++) {
        for (j = 0; j < itWidth; j++, y += 6, out += dst_pblock_stride) {
        uint16x8x3_t yy;
        yy.val[0] = vmovl_u8(vld1_u8(y));
        yy.val[1] = vmovl_u8(vld1_u8(y + width));
        yy.val[2] = vmovl_u8(vld1_u8(y + width2));
        int16x8_t m;
        m = vmulq_s16(yy.val[0], korg.val[0]);

        m = vmlaq_s16(m, yy.val[1], korg.val[1]);
        m = vmlaq_s16(m, yy.val[2], korg.val[2]);
        res = vsetq_lane_s16((vgetq_lane_s16(m, 0) + vgetq_lane_s16(m, 1) +
        vgetq_lane_s16(m, 2))/div, res, 0);
        res = vsetq_lane_s16((vgetq_lane_s16(m, 3) + vgetq_lane_s16(m, 4) +
        vgetq_lane_s16(m, 5))/div, res, 3);

        m = vmulq_s16(yy.val[0], k1.val[0]);
        m = vmlaq_s16(m, yy.val[1], k1.val[1]);
        m = vmlaq_s16(m, yy.val[2], k1.val[2]);
        res = vsetq_lane_s16((vgetq_lane_s16(m, 1) + vgetq_lane_s16(m, 2) +
        vgetq_lane_s16(m, 3))/div, res, 1);
        res = vsetq_lane_s16((vgetq_lane_s16(m, 4) + vgetq_lane_s16(m, 5) +
        vgetq_lane_s16(m, 6))/div, res, 4);

        m = vmulq_s16(yy.val[0], k2.val[0]);
        m = vmlaq_s16(m, yy.val[1], k2.val[1]);
        m = vmlaq_s16(m, yy.val[2], k2.val[2]);
        res = vsetq_lane_s16((vgetq_lane_s16(m, 2) + vgetq_lane_s16(m, 3) +
        vgetq_lane_s16(m, 4))/div, res, 2);
        res = vsetq_lane_s16((vgetq_lane_s16(m, 5) + vgetq_lane_s16(m, 6) +
        vgetq_lane_s16(m, 7))/div, res, 5);

        pblock.val[0] = vqmovun_s16(res);
        pblock.val[1] = pblock.val[0];
        pblock.val[2] = pblock.val[0];
        vst4_u8(out, pblock);
        }
        if (itWidthPad) {
        y = y-6+itWidthPad;
        out = out-(6-itWidthPad)*bytes_per_pixel;
        uint16x8x3_t yy;
        yy.val[0] = vmovl_u8(vld1_u8(y));
        yy.val[1] = vmovl_u8(vld1_u8(y + width));
        yy.val[2] = vmovl_u8(vld1_u8(y + width2));
        int16x8_t m;
        m = vmulq_s16(yy.val[0], korg.val[0]);
        m = vmlaq_s16(m, yy.val[1], korg.val[1]);
        m = vmlaq_s16(m, yy.val[2], korg.val[2]);
        res = vsetq_lane_s16((vgetq_lane_s16(m, 0) + vgetq_lane_s16(m, 1) +
        vgetq_lane_s16(m, 2))/div, res, 0);
        res = vsetq_lane_s16((vgetq_lane_s16(m, 3) + vgetq_lane_s16(m, 4) +
        vgetq_lane_s16(m, 5))/div, res, 3);

        m = vmulq_s16(yy.val[0], k1.val[0]);
        m = vmlaq_s16(m, yy.val[1], k1.val[1]);
        m = vmlaq_s16(m, yy.val[2], k1.val[2]);
        res = vsetq_lane_s16((vgetq_lane_s16(m, 1) + vgetq_lane_s16(m, 2) +
        vgetq_lane_s16(m, 3))/div, res, 1);
        res = vsetq_lane_s16((vgetq_lane_s16(m, 4) + vgetq_lane_s16(m, 5) +
        vgetq_lane_s16(m, 6))/div, res, 4);

        m = vmulq_s16(yy.val[0], k2.val[0]);
        m = vmlaq_s16(m, yy.val[1], k2.val[1]);
        m = vmlaq_s16(m, yy.val[2], k2.val[2]);
        res = vsetq_lane_s16((vgetq_lane_s16(m, 2) + vgetq_lane_s16(m, 3) +
        vgetq_lane_s16(m, 4))/div, res, 2);
        res = vsetq_lane_s16((vgetq_lane_s16(m, 5) + vgetq_lane_s16(m, 6) +
        vgetq_lane_s16(m, 7))/div, res, 5);

        pblock.val[0] = vqmovun_s16(res);
        pblock.val[1] = pblock.val[0];
        pblock.val[2] = pblock.val[0];
        vst4_u8(out, pblock);

        y += 8;
        out += 8 * bytes_per_pixel;
        }
        }
        }
        return 1;
    }
    extern "C"
    JNIEXPORT void JNICALL
    Java_com_example_procimagencamara_MainActivity_YUVtoNativeNEON(JNIEnv *env,
                                                                   jobject thiz, jint tipo,jbyteArray data, jintArray result,jint divisor,

                                                                   jbyteArray matrix,jint width, jint height,jint nthreads) {

        unsigned char *cData;
        int *cResult;
        cData = (unsigned char *) env->GetByteArrayElements(data,NULL);
        if (cData==NULL) __android_log_print(ANDROID_LOG_INFO, "HOOKnative", "No se puede obtener una referencia al array: data.");
        else {
            cResult= env->GetIntArrayElements(result,NULL);
            if (cResult==NULL) __android_log_print(ANDROID_LOG_INFO, "HOOKnative", "No se puede obtener una referencia al array: result.");
            else { // Ejecuta la opción seleccionada.
                if(tipo==0) {
                    if(nthreads>1) {
                        convertYUV420_NV21toRGB8888_NEON(cData,cResult,width,height,1);
                    } else {
                        convertYUV420_NV21toRGB8888_NEON(cData,cResult,width,height,0);
                    }
                } else if(tipo==1) {
                    if(nthreads>1) {
                        convertYUV420_NV21toGreyScale_NEON(cData,cResult,width,height,1);
                    } else {
                        convertYUV420_NV21toGreyScale_NEON(cData,cResult,width,height,0);
                    }
                } else if(tipo==2) {
                    if(nthreads>1) {
                        char *cMatrix = (char *) env->GetByteArrayElements(matrix, NULL);
                        convertYUV420_Convolution_NEON(cData,cResult,width,height,cMatrix, divisor, 1);
                        if(cMatrix!=NULL) env->ReleaseByteArrayElements(matrix, (jbyte *) cMatrix, 0);
                    } else {
                        char *cMatrix = (char *) env->GetByteArrayElements(matrix, NULL);
                        convertYUV420_Convolution_NEON(cData,cResult,width,height, cMatrix,divisor, 0);
                        if(cMatrix!=NULL) env->ReleaseByteArrayElements(matrix, (jbyte *) cMatrix, 0);
                    }
                }
            }
        }
        if (cResult!=NULL) env->ReleaseIntArrayElements(result,cResult,0);
        if (cData!=NULL) env->ReleaseByteArrayElements(data, (jbyte *)cData, 0);
    }
    extern "C"
    JNIEXPORT jboolean JNICALL
    Java_com_example_procimagencamara_MainActivity_isNEONSupported(JNIEnv *env, jobject thiz) {
        return JNI_TRUE;
    }

    */
#else
    extern "C"
    JNIEXPORT void JNICALL

    Java_com_example_procimagencamara_MainActivity_YUVtoNativeNEON(JNIEnv *env,
                                                                   jobject thiz, jint tipo,jbyteArray data, jintArray result,jint divisor,

                                                                   jbyteArray matrix,jint width, jint height,jint nthreads) {

        __android_log_print(ANDROID_LOG_INFO, "HOOKnative",

                            "Instrucciones NEON no soportadas.");

    }
    extern "C"
    JNIEXPORT jboolean JNICALL
    Java_com_example_procimagencamara_MainActivity_isNEONSupported(JNIEnv *env, jobject thiz) {
        return JNI_FALSE;
    }
#endif