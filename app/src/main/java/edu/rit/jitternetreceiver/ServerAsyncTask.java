package edu.rit.jitternetreceiver;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.AsyncTask;
import android.util.Log;

import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * Created by lukesun on 4/3/15.
 */
public class ServerAsyncTask extends AsyncTask<Socket, Void, String> {

    private Socket clientSocket = null;
    private boolean waiting;

    //Jitter Packet parser
    private String _JMTX = "JMTX";
    private String _JMLP = "JMLP"; //Not used
    private String _JMMP = "JMMP"; //Not used
    //This value indicates that the stream is suddenly end and should be parsed from beginning.
    private float _f_header_check_value = 3364118.0f;
    private boolean isJitHeader; //True if the current packet is the header
    private int jitPlaneCount; //Jit matrix plane count
    private long jitType; //Jit matrix data type
    private long jitDimCount; //The number of dimension
    private int[] jitDim = new int[32]; //The length of each dimension
    private int jitDataSize; //The total packet size in bytes, including header and body
    private int numHeaderSize; //The current byte array size of the header
    private int numBodySize; //The current byte array size of the body
    private int numBodyCount; //The number of the body data read, counted in float32

    // Stream loop control
    byte[] scanByte;
    private int DATA_SCAN_SIZE = 8192; //The size of each time data read from input stream
    private int numScanned; //The actual data size read from input stream
    private int numRead; //The data size to be write to audio track

    // Audio track configure
    static final int sampleRate = 44100;
    static final int channelConfiguration = AudioFormat.CHANNEL_OUT_MONO;
    static final int audioEncoding = AudioFormat.ENCODING_PCM_16BIT;
    private int audioBufferSize;
    private AudioTrack audioTrack;
    private byte[] audioSwapBuffer;
    private int audioBufferCount;

    public ServerAsyncTask() {
        super();

        audioBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfiguration, audioEncoding);
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelConfiguration, audioEncoding, audioBufferSize, AudioTrack.MODE_STREAM);
        audioBufferCount = 0;
    }

    @Override
    protected String doInBackground(Socket... params) {
        // Check socket connection
        clientSocket = params[0];
        if (clientSocket == null) {
            return "error";
        }

        // Init connection info
        String clientIP = clientSocket.getInetAddress().getHostAddress();
        int clientPort = clientSocket.getPort();
        Log.d("CONNECTION", clientIP + ": " + clientPort + " connected. ");

        try {
            waiting = true;
            audioTrack.play();

            //Set initial value for the first variables
            //Always consider the first incoming packet is the header packet
            isJitHeader = true;
            numScanned = 0;
            numRead = 0;
            numHeaderSize = 0;
            numBodySize = 0;

            //Input stream scanning loop
            scanByte = new byte[DATA_SCAN_SIZE];
            //The data left since the last scanning
            int ires = 0;
            while (waiting) {
                //Get input stream encoding for byte <-> char conversion
                String charEncoding = new InputStreamReader(clientSocket.getInputStream()).getEncoding();
//                Log.d("SCANNING", "charEncoding: " + charEncoding);

                numScanned += clientSocket.getInputStream().read(scanByte, numScanned, scanByte.length - numScanned);
                //Data read loop
                while (numScanned - ires > 0) {
//                    Log.d("INFO", "ByteBuffer.wrap, scanByte.length: " + scanByte.length + ", numScanned: " + numScanned);
                    ByteBuffer scanBuffer = ByteBuffer.wrap(scanByte, 0, numScanned);
                    if (isJitHeader) {
                        ires = parseHeader(scanBuffer, charEncoding);
                    } else {
                        ires = parseBody(scanBuffer, charEncoding);
                    }

                    //End scanning
                    if (ires < 0)
                        return "error";
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return "success";
    }

    //Jit Network Header Parser
    private int parseHeader(ByteBuffer sourceBuffer, String charEncoding) {
        // If the length of buffer left is less than header
        // Just waiting for the next input
        if (sourceBuffer.limit() < numHeaderSize) {
            return sourceBuffer.limit();
        }

        byte[] worker = new byte[4];

        try {
            //Packet ID
            sourceBuffer.get(worker, 0, worker.length);
            if (!(new String(worker, 0, worker.length, charEncoding).equals(_JMTX))) {
//                Log.e("ERROR", "Unknown package ID: " + new String(worker, 0, worker.length, charEncoding));
                return -1;
            }
            numRead += worker.length;
//            Log.d("INFO", "Package ID: JMTX");

            //Skip 8 bytes for unknown reason
            numRead += 8;

            //Get header size + 8 unknown gap at last
            numHeaderSize = sourceBuffer.getInt(numRead) + 8;
            numRead += worker.length;
//            Log.d("INFO", "numHeaderSize: " + numHeaderSize);

            //Jit matrix plane count
            jitPlaneCount = sourceBuffer.getInt(numRead);
            numRead += worker.length;
//            Log.d("INFO", "jitPlaneCount: " + jitPlaneCount);

            ////Jit matrix data type
            //According to https://docs.cycling74.com/max5/refpages/jit-ref/jit.release~.html
            //Should be type 2, float32 jitter matrices
            jitType = sourceBuffer.getInt(numRead);
            if (jitType != 2) {
                Log.e("ERROR", "Unknown jitType: " + jitType);
                return -1;
            }
            numRead += worker.length;
//            Log.d("INFO", "jitType: " + jitType);

            //The number of dimension
            //By testing, it is 1D.
            jitDimCount  = sourceBuffer.getInt(numRead);
            if (jitDimCount != 1) {
                Log.e("ERROR", "Unknown jitDimCount: " + jitDimCount);
                return -1;
            }
            numRead += worker.length;
//            Log.d("INFO", "jitDimCount: " + jitDimCount);

            //32 dim array
            for (int i = 0; i < 32; i++) {
                jitDim[i] = sourceBuffer.getInt(numRead);
                numRead += worker.length;
            }
            // Since the audio buffer is 1D, only jitDim[0] is working
            // Usually, the size is 512
//            Log.d("INFO", "jitDim 0: " + jitDim[0]);

            //Unknown gap
            numRead += 128;

            //The total packet size in bytes
            // Usually, the size is 2048
            jitDataSize = sourceBuffer.getInt(numRead);
            numRead += worker.length;
//            Log.d("INFO", "jitDataSize: " + jitDataSize);

            //Get body size
//            numBodySize = jitDataSize - numHeaderSize;
            numBodySize = jitDataSize;
//            Log.d("INFO", "numBodySize: " + numBodySize);

            //Unknown gap
            numRead += 8;

            if (numRead == numHeaderSize) {
//                Log.d("INFO", "End of parseHeader, numRead: " + numRead);
                //Refill result buffer
                int numByteLeft = sourceBuffer.limit() - numRead;
//                Log.e("INFO", "End of parseHeader, numByteLeft: " + numByteLeft);
                scanByte = new byte[DATA_SCAN_SIZE];
//                Log.e("INFO", "End of parseHeader, result.length: " + result.length);
                sourceBuffer.position(numRead);
//                Log.e("INFO", "End of parseHeader, sourceBuffer.position: " + sourceBuffer.position());
                if (numByteLeft > 0)
                    sourceBuffer.get(scanByte, 0, numByteLeft);
                //Debug
//                ByteBuffer temp = ByteBuffer.wrap(result, 0, numByteLeft);
//                FloatBuffer floatBuffer = temp.asFloatBuffer();
//                float[] sourceFloat = new float[floatBuffer.limit()];
//                Log.e("INFO", "End of parseHeader, floatBuffer.limit(): " + floatBuffer.limit());
//                floatBuffer.get(sourceFloat);
//                int lineBreak = 1;
//                if (sourceFloat.length > 128)
//                    lineBreak = sourceFloat.length/128;
//
//                for (int i = 0; i < lineBreak - 1; i++) {
//                    Log.e("INFO", "message float: " + Arrays.toString(Arrays.copyOfRange(sourceFloat, i * sourceFloat.length/lineBreak, (i + 1) * sourceFloat.length/lineBreak)));
//                }
//                Log.e("INFO", "message float: " + Arrays.toString(Arrays.copyOfRange(sourceFloat, (lineBreak - 1) * sourceFloat.length/lineBreak, sourceFloat.length - 1)));
                numScanned -= numRead;
                numRead = 0;
                isJitHeader = false;
                numBodyCount = 0;
                return 0;
            } else {
                Log.e("ERROR", "parseHeader, numRead: " + numRead);
                return -1;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return 0;
    }

    //Parse jit.matrix
    private int parseBody(ByteBuffer sourceBuffer, String charEncoding) {
        //Notice: it is impossible that the length of source buffer would be less than 4.
        FloatBuffer floatBuffer = sourceBuffer.asFloatBuffer();
        //The length of matrix data should be 1/4 of the body size.
        //The body size should always be able to be divided by 4.
        int floatLength = floatBuffer.limit();
        if (floatLength + numBodyCount > numBodySize/4)
            floatLength = numBodySize/4 - numBodyCount;

        float[] sourceFloat = new float[floatLength];
        floatBuffer.get(sourceFloat);
        //Check if a new header is coming at unexpected position.
//        int checkEnd = ArrayUtils.indexOf(sourceFloat, _f_header_check_value);
//        if (checkEnd >=0 ) {
//            Log.e("INFO", "checkEnd size: " + checkEnd);
//            sourceFloat = new float[checkEnd];
//            floatBuffer.position(0);
//            floatBuffer.get(sourceFloat);
//        }
        numRead = sourceFloat.length * 4;
        Log.d("INFO", "sourceFloat size: " + sourceFloat.length);

        genTone(sourceFloat);

        //For debugging: print data
//        int lineBreak = 1;
//        if (sourceFloat.length > 128)
//            lineBreak = sourceFloat.length/128;
//        for (int i = 0; i < lineBreak - 1; i++) {
//            Log.e("INFO", "message float: " + Arrays.toString(Arrays.copyOfRange(sourceFloat, i * sourceFloat.length/lineBreak, (i + 1) * sourceFloat.length/lineBreak)));
//        }
//        Log.e("INFO", "message float: " + Arrays.toString(Arrays.copyOfRange(sourceFloat, (lineBreak - 1) * sourceFloat.length/lineBreak, sourceFloat.length)));

        //Refill result buffer
        int numByteLeft = sourceBuffer.limit() - numRead;
        scanByte = new byte[DATA_SCAN_SIZE];
        sourceBuffer.position(numRead);
        if (numByteLeft > 0)
            sourceBuffer.get(scanByte, 0, numByteLeft);
        numScanned -= numRead;
        numRead = 0;
        if (floatLength + numBodyCount == numBodySize/4) {
//            Log.d("INFO", "End of parseBody, reset to read header...");
            isJitHeader = true;
            numBodyCount = 0;
        } else {
            numBodyCount += floatLength;
        }

        return 0;
    }

    //Write data to audio track
    private void genTone(float[] data) {
        if (data == null && data.length < 1)
            return;

        //Extend current swap buffer for new data
        byte generatedTone[] = new byte[audioBufferCount + 2 * data.length];
        if (audioBufferCount > 0) {
            ByteBuffer tempGenBuffer = ByteBuffer.wrap(audioSwapBuffer, 0, audioBufferCount);
            tempGenBuffer.get(generatedTone, 0, tempGenBuffer.limit());
//            Log.d("INFO", "tempGenBuffer.limit(): " + tempGenBuffer.limit());
        }

        // convert to 16 bit pcm sound array
        // assumes the sample buffer is normalised.
        int idx = audioBufferCount;
        for (float fVal : data) {
            if (fVal < -1.0f || fVal > 1.0f)
                Log.e("ERROR", "message float out of range: " + fVal);

            short val = (short) (fVal * 32767);
//            if( val > 32767 ) val = 32767;
//            if( val < -32768 ) val = -32768;
            generatedTone[idx++] = (byte) (val & 0x00ff);
            generatedTone[idx++] = (byte) ((val & 0xff00) >>> 8);
        }
        audioBufferCount = generatedTone.length;
        Log.d("INFO", "audioBufferCount: " + audioBufferCount);

        int audioWritePosition = 0;
        while (audioBufferCount >= audioBufferSize) {
            audioBufferCount -= audioBufferSize;
            audioTrack.write(generatedTone, audioWritePosition, audioBufferSize);
            audioWritePosition += audioBufferSize;
        }

        if (audioBufferCount > 0) {
            Log.e("INFO", "audioBufferCount left: " + audioBufferCount);
            audioSwapBuffer = new byte[audioBufferCount];
            ByteBuffer tempSwapBuffer = ByteBuffer.wrap(generatedTone, audioWritePosition, audioBufferCount);
            tempSwapBuffer.get(audioSwapBuffer, 0, audioBufferCount);
        }
    }

    /**
     * Round to certain number of decimals
     *
     * @param d
     * @param decimalPlace
     * @return
     */
    public static float roundUP(float d, int decimalPlace) {
        BigDecimal bd = new BigDecimal(Float.toString(d));
        bd = bd.setScale(decimalPlace, BigDecimal.ROUND_HALF_UP);
        return bd.floatValue();
    }

    //Just for debugging
    private void showUnknownContent() {
        Log.e("INFO", "showUnknownContent");
        byte[] jitDataTest = new byte[4096];
//        long numSkip = 0;
        int numTest = 0;
        while (true) {
            try {
//                numSkip = clientSocket.getInputStream().skip(296);
//                Log.d("INFO", "numSkip: " + numSkip);
                numTest = clientSocket.getInputStream().read(jitDataTest, 0, jitDataTest.length);
                Log.d("INFO", "numTest: " + numTest);
                if (numTest < 0)
                    break;
            } catch (IOException e) {
                e.printStackTrace();
            }

            ByteBuffer buffer = ByteBuffer.wrap(jitDataTest, 0, numTest);
            FloatBuffer floatBuffer = buffer.asFloatBuffer();
//            CharBuffer charBuffer = buffer.asCharBuffer();
//            IntBuffer intBuffer = buffer.asIntBuffer();

            int lineBreak = 1;

            float[] audioFloat = new float[floatBuffer.limit()];
            floatBuffer.get(audioFloat);
            Log.e("INFO", "audioFloat size: " + audioFloat.length);
            if (audioFloat.length > 128)
                lineBreak = audioFloat.length/128;
            for (int i = 0; i < lineBreak - 1; i++) {
                Log.e("INFO", "message float: " + Arrays.toString(Arrays.copyOfRange(audioFloat, i * audioFloat.length/lineBreak, (i + 1) * audioFloat.length/lineBreak - 1)));
            }
            Log.e("INFO", "message float: " + Arrays.toString(Arrays.copyOfRange(audioFloat, (lineBreak - 1) * audioFloat.length/lineBreak, audioFloat.length - 1)));

//        char[] audioChar = new char[charBuffer.limit()];
//        charBuffer.get(audioChar);
//        Log.e("INFO", "audioChar size: " + audioChar.length);
//        lineBreak = audioChar.length/128;
//        for (int i = 0; i < lineBreak - 1; i++) {
//            Log.e("INFO", "message char: " + Arrays.toString(Arrays.copyOfRange(audioChar, i * audioChar.length/lineBreak, audioChar.length/lineBreak)));
//        }
//        Log.e("INFO", "message char: " + Arrays.toString(Arrays.copyOfRange(audioChar, (lineBreak - 1) * audioChar.length/lineBreak, audioChar.length - (lineBreak - 1) * audioChar.length/lineBreak)));

//        int[] audioInt = new int[intBuffer.limit()];
//        intBuffer.get(audioInt);
//        Log.e("INFO", "audioInt size: " + audioInt.length);
//        lineBreak = audioInt.length/128;
//        for (int i = 0; i < lineBreak - 1; i++) {
//            Log.e("INFO", "message int: " + Arrays.toString(Arrays.copyOfRange(audioInt, i * audioInt.length/lineBreak, audioInt.length/lineBreak)));
//        }
//        Log.e("INFO", "message int: " + Arrays.toString(Arrays.copyOfRange(audioInt, (lineBreak - 1) * audioInt.length/lineBreak, audioInt.length - (lineBreak - 1) * audioInt.length/lineBreak)));

            genTone(audioFloat);
        }
    }

    //TODO: FIXME
    //Reset connection
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
}
