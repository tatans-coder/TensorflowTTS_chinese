package net.tatans.tensorflowtts.tts;

import android.content.Context;
import android.util.Log;

import net.tatans.tensorflowtts.dispatcher.TtsStateDispatcher;
import net.tatans.tensorflowtts.module.FastSpeech2;
import net.tatans.tensorflowtts.module.MBMelGan;
import net.tatans.tensorflowtts.utils.Processor;
import net.tatans.tensorflowtts.utils.ThreadPoolManager;
import net.tatans.tensorflowtts.utils.ZhProcessor;

import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author {@link "mailto:xuefeng.ding@outlook.com" "Xuefeng Ding"}
 * Created 2020-07-28 14:25
 */
class InputWorker {
    private static final String TAG = "InputWorker";

    private LinkedBlockingQueue<InputText> mInputQueue = new LinkedBlockingQueue<>();
    private InputText mCurrentInputText;
    private FastSpeech2 mFastSpeech2;
    private MBMelGan mMBMelGan;
    private Processor mProcessor;
    private TtsPlayer mTtsPlayer;
    private ZhProcessor zhProcessor;
    private Context context;

    InputWorker(Context context, String fastspeech, String vocoder) {
        this.context = context;
        mFastSpeech2 = new FastSpeech2(fastspeech);
        mMBMelGan = new MBMelGan(vocoder);
        mProcessor = new Processor();
        mTtsPlayer = new TtsPlayer();
        zhProcessor = new ZhProcessor(context);

        ThreadPoolManager.getInstance().getSingleExecutor("worker").execute(() -> {
            //noinspection InfiniteLoopStatement
            while (true) {
                try {
                    mCurrentInputText = mInputQueue.take();
                    Log.d(TAG, "processing: " + mCurrentInputText.INPUT_TEXT);
                    TtsStateDispatcher.getInstance().onTtsStart(mCurrentInputText.INPUT_TEXT);
                    mCurrentInputText.proceed();
                    TtsStateDispatcher.getInstance().onTtsStop();
                } catch (Exception e) {
                    Log.e(TAG, "Exception: ", e);
                }
            }
        });
    }

    void processInput(String inputText, float speed) {
        Log.d(TAG, "add to queue: " + inputText);
        mInputQueue.offer(new InputText(inputText, speed));
    }

    void interrupt() {
        mInputQueue.clear();
        if (mCurrentInputText != null) {
            mCurrentInputText.interrupt();
        }
        mTtsPlayer.interrupt();
    }


    private class InputText {
        private final String INPUT_TEXT;
        private final float SPEED;
        private boolean isInterrupt;

        private InputText(String inputText, float speed) {
            this.INPUT_TEXT = inputText;
            this.SPEED = speed;
        }

        private void proceed() {
            String[] sentences = INPUT_TEXT.split("[\n，。？?！!,;；]");
            Log.d(TAG, "speak: " + Arrays.toString(sentences));

            for (String sentence : sentences) {

                long time = System.currentTimeMillis();

//                int[] inputIds = mProcessor.textToIds(sentence);
                int[] inputIds = zhProcessor.text2ids(sentence);
                TensorBuffer output = mFastSpeech2.getMelSpectrogram(inputIds, SPEED);

                if (isInterrupt) {
                    Log.d(TAG, "proceed: interrupt");
                    return;
                }

                long encoderTime = System.currentTimeMillis();

                float[] audioData;
                try {
                    audioData = mMBMelGan.getAudio(output);
                } catch (Exception e) {
                    e.printStackTrace();
                    continue;
                }

                if (isInterrupt) {
                    Log.d(TAG, "proceed: interrupt");
                    return;
                }

                long vocoderTime = System.currentTimeMillis();

                Log.d(TAG, "Time cost: " + (encoderTime - time) + "+" + (vocoderTime - encoderTime) + "=" + (vocoderTime - time));

                mTtsPlayer.play(new TtsPlayer.AudioData(sentence, audioData));
            }
        }

        private void interrupt() {
            this.isInterrupt = true;
        }
    }

}