package edu.rit.jitternetreceiver;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.AsyncTask;
import android.util.Log;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.Conversion;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * Created by lukesun on 4/3/15.
 */
public class ServerAsyncTask extends AsyncTask<Socket, Void, String> {

    private Socket clientSocket = null;

    private boolean waiting;

    //Packet parser
    private boolean header;
    private boolean dataRead;
    private int jitPlanecount;
    private long jitType;
    private long jitDimcount;
    private int[] jitDim = new int[32];
    private int jitDataWidth;
    private int jitDatasize;
//    private int[] jitTexData;
    private byte[] jitData;
    private int numread;

    static final int sampleRate = 44100;
//    static final int channelConfiguration = AudioFormat.CHANNEL_OUT_STEREO;
    static final int channelConfiguration = AudioFormat.CHANNEL_OUT_MONO;
    static final int audioEncoding = AudioFormat.ENCODING_PCM_16BIT;
    int audioBufferSize;
    AudioTrack audioTrack;

    public ServerAsyncTask() {
        super();

        audioBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfiguration, audioEncoding);
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelConfiguration, audioEncoding, audioBufferSize, AudioTrack.MODE_STREAM);
        Log.e("INFO", "audioBufferSize: " + audioBufferSize);
    }

    @Override
    protected String doInBackground(Socket... params) {
        char[] worker = new char[4];
        char[] gap = new char[128];

        // JitterNetSender
        clientSocket = params[0];
        if (clientSocket != null) {
            String clientIP = clientSocket.getInetAddress().getHostAddress();
            int clientPort = clientSocket.getPort();
            Log.d("Connected", clientIP + ": " + clientPort);

            header = false;
            waiting = true;
        }

        audioTrack.play();

        try {
            while (waiting) {
                BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                if (!header) {
                    //HEADER
                    numread = 0;
                    dataRead = false;

                    numread += in.read(worker, 0, worker.length);
                    if (Arrays.equals(new String("JMTX").toCharArray(), worker)) {
                        Log.d("INFO", "Package ID: JMTX");

                        //no idea why + 4 bytes for header size
                        numread += in.read(gap, 0, 12);

                        //planecount
                        numread += in.read(worker, 0, worker.length);
                        ArrayUtils.reverse(worker);
                        jitPlanecount = Conversion.byteArrayToInt(new String(worker).getBytes(), 0, 0, 0, worker.length);
                        Log.d("INFO", "jitPlanecount: " + jitPlanecount);

                        //Type
                        //According to https://docs.cycling74.com/max5/refpages/jit-ref/jit.release~.html
                        //Should be type 2, float32 jitter matrices
                        numread += in.read(worker, 0, worker.length);
                        ArrayUtils.reverse(worker);
                        jitType = Conversion.byteArrayToInt(new String(worker).getBytes(), 0, 0, 0, worker.length);
                        Log.d("INFO", "jitType: " + jitType);
                        if (jitType == 2) {
                            //Dimcount
                            //By testing, it is 1D.
                            numread += in.read(worker, 0, worker.length);
                            ArrayUtils.reverse(worker);
                            jitDimcount = Conversion.byteArrayToInt(new String(worker).getBytes(), 0, 0, 0, worker.length);
                            Log.d("INFO", "jitDimcount: " + jitDimcount);

                            if (jitDimcount == 1) {
                                //32 dim array
                                for (int i = 0; i < 32; i++) {
                                    numread += in.read(worker, 0, worker.length);
                                    ArrayUtils.reverse(worker);
                                    jitDim[i] = Conversion.byteArrayToInt(new String(worker).getBytes(), 0, 0, 0, worker.length);
                                }
                                // Since the audio buffer is 1D, only the jitDim[0] is working
                                // Usually, the size is 512
                                Log.d("INFO", "jitDim 0: " + jitDim[0]);
                            }

                            //If dimensions have changed
                            if (jitDim[0] != jitDataWidth)
                            {
                                jitDataWidth = jitDim[0];
                            }

                            numread += in.read(gap, 0, 128);

                            //Datasize
                            // Usually, the size is 2048
                            numread += in.read(worker, 0, worker.length);
                            ArrayUtils.reverse(worker);
                            jitDatasize = Conversion.byteArrayToInt(new String(worker).getBytes(), 0, 0, 0, worker.length);
                            Log.d("INFO", "jitDatasize: " + jitDatasize);

                            jitData = new byte[jitDatasize];
                            numread = 0;
                            header = true;

                        } else {
                            Log.e("Error", "jitType: " + jitType);
                            in.reset();
                        }
                    } else if (Arrays.equals(new String("JMLP").toCharArray(), worker)) {
                        Log.d("INFO", "Package ID: JMLP");
                        in.reset();
                    } else if (Arrays.equals(new String("JMMP").toCharArray(), worker)) {
                        Log.d("INFO", "Package ID: JMMP");
                        in.reset();
                    }
                } else if (header) {
                    // Getting real audio data;
                    if (numread < 0)
                        return null;

                    numread += clientSocket.getInputStream().read(jitData, numread, jitData.length - numread);
//                    Log.e("INFO", "numread: " + numread + "%");

                    if (numread == jitData.length) {
                        if (!dataRead) {
//                            Log.e("INFO", "message byte: " + new String(jitData));
                            int pBufferNum = 0;
                            ByteBuffer jitBuffer = ByteBuffer.wrap(jitData);
                            CharBuffer charBuffer = jitBuffer.asCharBuffer();

                            // Try to parse data package
//                            charBuffer.get(worker, pBufferNum, worker.length);
//                            pBufferNum += worker.length;
//                            ArrayUtils.reverse(worker);
//                            long serialSize = Conversion.byteArrayToLong(jitData, 0, 0, 0, worker.length);
//                            Log.e("INFO", "serialSize: " + serialSize);

                            //Write data (2048) to audio buffer;
//                            float[] audioFloat = toFloatArray(jitBuffer);
                            float[] audioFloat = {
                                0.306306f,0.366534f,0.425264f,0.482253f,0.53727f,0.590088f,0.640493f,0.688277f,0.733246f,0.775215f,0.814012f,0.849479f,0.881472f,0.909858f,0.934522f,0.955363f,
                                0.972295f,0.98525f,0.994174f,0.999031f,0.999801f,0.996481f,0.989084f,0.977641f,0.962199f,0.94282f,0.919584f,0.892586f,0.861936f,0.827761f,0.790199f,0.749404f,
                                0.705544f,0.658797f,0.609355f,0.55742f,0.503205f,0.446931f,0.388829f,0.329136f,0.268096f,0.20596f,0.142981f,0.079418f,0.015529f,-0.048423f,-0.112177f,-0.175472f,
                                -0.238049f,-0.299653f,-0.36003f,-0.418935f,-0.476125f,-0.531368f,-0.584438f,-0.635116f,-0.683196f,-0.728481f,-0.770786f,-0.809937f,-0.845775f,-0.878153f,-0.906939f,
                                -0.932014f,-0.953277f,-0.970639f,-0.984031f,-0.993397f,-0.998699f,-0.999916f,-0.997042f,-0.990089f,-0.979085f,-0.964076f,-0.945124f,-0.922304f,-0.895712f,-0.865455f,
                                -0.831658f,-0.794458f,-0.754008f,-0.710474f,-0.664033f,-0.614875f,-0.563202f,-0.509225f,-0.453165f,-0.395251f,-0.33572f,-0.274816f,-0.212787f,-0.149888f,-0.086375f,
                                -0.022509f,0.041449f,0.105237f,0.168595f,0.231263f,0.292985f,0.353508f,0.412585f,0.469975f,0.525441f,0.578758f,0.629708f,0.678081f,0.72368f,0.766319f,0.805823f,0.84203f,
                                0.874792f,0.903976f,0.929461f,0.951145f,0.968937f,0.982765f,0.992572f,0.998319f,0.999982f,0.997554f,0.991045f,0.980482f,0.965907f,0.947381f,0.92498f,0.898794f,0.868931f,
                                0.835514f,0.798678f,0.758575f,0.715369f,0.669236f,0.620366f,0.568957f,0.515221f,0.459377f,0.401654f,0.342288f,0.281521f,0.219603f,0.156786f,0.093328f,0.029488f,-0.034472f,
                                -0.098292f,-0.161709f,-0.224465f,-0.286303f,-0.346969f,-0.406216f,-0.463801f,-0.519489f,-0.573051f,-0.624269f,-0.672933f,-0.718845f,-0.761815f,-0.801669f,-0.838244f,-0.871389f,
                                -0.900969f,-0.926863f,-0.948966f,-0.967186f,-0.98145f,-0.991699f,-0.99789f,-0.999999f,-0.998018f,-0.991953f,-0.98183f,-0.967691f,-0.949593f,-0.92761f,-0.901833f,-0.872365f,
                                -0.83933f,-0.80286f,-0.763106f,-0.72023f,-0.674408f,-0.625826f,-0.574685f,-0.521192f,-0.465567f,-0.408038f,-0.348839f,-0.288213f,-0.226408f,-0.163677f,-0.100277f,-0.036466f,
                                0.027494f,0.091342f,0.154816f,0.217657f,0.279607f,0.340413f,0.399827f,0.457605f,0.513511f,0.567316f,0.6188f,0.667753f,0.713974f,0.757274f,0.797476f,0.834416f,0.867942f,
                                0.897918f,0.92422f,0.946741f,0.965389f,0.980088f,0.990777f,0.997413f,0.999968f,0.998433f,0.992813f,0.983131f,0.969428f,0.951758f,0.930195f,0.904827f,0.875757f,0.843104f,
                                0.807002f,0.767599f,0.725056f,0.679546f,0.631256f,0.580384f,0.527137f,0.471734f,0.414402f,0.355373f,0.294891f,0.233203f,0.17056f,0.10722f,0.043441f,-0.020515f,-0.084388f,
                                -0.147915f,-0.210837f,-0.272897f,-0.33384f,-0.393418f,-0.451386f,-0.507508f,-0.561553f,-0.613301f,-0.66254f,-0.709069f,-0.752696f,-0.793245f
                            };
                            Log.e("INFO", "audioFloat size: " + audioFloat.length);
                            Log.e("INFO", "message float: " + Arrays.toString(Arrays.copyOfRange(audioFloat, 0, 255)));
                            Log.e("INFO", "message float: " + Arrays.toString(Arrays.copyOfRange(audioFloat, 256, audioFloat.length - 1)));

                            // convert to 16 bit pcm sound array
                            // assumes the sample buffer is normalised.
                            byte generatedSnd[] = new byte[2*audioFloat.length];
//                            ByteBuffer generatedSnd = ByteBuffer.allocate(2*audioFloat.length);
                            int idx = 0;
                            for (float fVal : audioFloat) {
                                short val = (short) (fVal * 32768);
                                if( val > 32767 ) val = 32767;
                                if( val < -32768 ) val = -32768;
                                generatedSnd[idx++] = (byte) (val & 0xff);
                                generatedSnd[idx++] = (byte) ((val >> 8) & 0xff);
                            }
                            audioTrack.write(generatedSnd, 0, generatedSnd.length);

                            dataRead = true;
                        }
                        header = false;
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return "success";
    }

    private void Reset()
    {
        waiting = false;
        audioTrack.stop();
        try {
            if (clientSocket != null)
                clientSocket.close();
        } catch (Exception e) { e.printStackTrace(); }
        clientSocket = null;
    }

    private static float[] toFloatArray(ByteBuffer buffer) {
        FloatBuffer fb = buffer.asFloatBuffer();

        float[] floatArray = new float[fb.limit()];
        fb.get(floatArray);

        return floatArray;
    }
}
