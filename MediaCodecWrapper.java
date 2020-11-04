package com.example.android.common.media;

import android.media.*;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Queue;

public class MediaCodecWrapper {

    private Handler mHandler;

    public interface OutputFormatChangedListener {
        void outputFormatChanged(MediaCodecWrapper sender, MediaFormat newFormat);
    }

    private OutputFormatChangedListener mOutputFormatChangedListener = null;

    
    public interface OutputSampleListener {
        void outputSample(MediaCodecWrapper sender, MediaCodec.BufferInfo info, ByteBuffer buffer);
    }


    private MediaCodec mDecoder;

    .
    private ByteBuffer[] mInputBuffers;
    private ByteBuffer[] mOutputBuffers;

    
    private Queue<Integer> mAvailableInputBuffers;

    
    private Queue<Integer> mAvailableOutputBuffers;

    // Information about each output buffer, by index. Each entry in this array
    // is valid if and only if its index is currently contained in mAvailableOutputBuffers.
    private MediaCodec.BufferInfo[] mOutputBufferInfo;

    private MediaCodecWrapper(MediaCodec codec) {
        mDecoder = codec;
        codec.start();
        mInputBuffers = codec.getInputBuffers();
        mOutputBuffers = codec.getOutputBuffers();
        mOutputBufferInfo = new MediaCodec.BufferInfo[mOutputBuffers.length];
        mAvailableInputBuffers = new ArrayDeque<>(mOutputBuffers.length);
        mAvailableOutputBuffers = new ArrayDeque<>(mInputBuffers.length);
    }

    /**
     * Releases resources and ends the encoding/decoding session.
     */
    public void stopAndRelease() {
        mDecoder.stop();
        mDecoder.release();
        mDecoder = null;
        mHandler = null;
    }

    /**
     * Getter for the registered {@link OutputFormatChangedListener}
     */
    public OutputFormatChangedListener getOutputFormatChangedListener() {
        return mOutputFormatChangedListener;
    }

    /**
     *
     * @param outputFormatChangedListener the listener for callback.
     * @param handler message handler for posting the callback.
     */
    public void setOutputFormatChangedListener(final OutputFormatChangedListener
            outputFormatChangedListener, Handler handler) {
        mOutputFormatChangedListener = outputFormatChangedListener;

        // Making sure we don't block ourselves due to a bad implementation of the callback by
        // using a handler provided by client.
        mHandler = handler;
        if (outputFormatChangedListener != null && mHandler == null) {
            if (Looper.myLooper() != null) {
                mHandler = new Handler();
            } else {
                throw new IllegalArgumentException(
                        "Looper doesn't exist in the calling thread");
            }
        }
    }

    
    public static MediaCodecWrapper fromVideoFormat(final MediaFormat trackFormat,
            Surface surface) throws IOException {
        MediaCodecWrapper result = null;
        MediaCodec videoCodec = null;

        // BEGIN_INCLUDE(create_codec)
        final String mimeType = trackFormat.getString(MediaFormat.KEY_MIME);

        // Check to see if this is actually a video mime type. If it is, then create
        // a codec that can decode this mime type.
        if (mimeType.contains("video/")) {
            videoCodec = MediaCodec.createDecoderByType(mimeType);
            videoCodec.configure(trackFormat, surface, null,  0);

        }

        // If codec creation was successful, then create a wrapper object around the
        // newly created codec.
        if (videoCodec != null) {
            result = new MediaCodecWrapper(videoCodec);
        }
        // END_INCLUDE(create_codec)

        return result;
    }
 
    public boolean writeSample(final ByteBuffer input,
            final MediaCodec.CryptoInfo crypto,
            final long presentationTimeUs,
            final int flags) throws MediaCodec.CryptoException, WriteException {
        boolean result = false;
        int size = input.remaining();

        // check if we have dequed input buffers available from the codec
        if (size > 0 &&  !mAvailableInputBuffers.isEmpty()) {
            int index = mAvailableInputBuffers.remove();
            ByteBuffer buffer = mInputBuffers[index];

            // we can't write our sample to a lesser capacity input buffer.
            if (size > buffer.capacity()) {
                throw new MediaCodecWrapper.WriteException(String.format(Locale.US,
                        "Insufficient capacity in MediaCodec buffer: "
                            + "tried to write %d, buffer capacity is %d.",
                        input.remaining(),
                        buffer.capacity()));
            }

            buffer.clear();
            buffer.put(input);

            
            if (crypto == null) {
                mDecoder.queueInputBuffer(index, 0, size, presentationTimeUs, flags);
            } else {
                mDecoder.queueSecureInputBuffer(index, 0, crypto, presentationTimeUs, flags);
            }
            result = true;
        }
        return result;
    }

    private static MediaCodec.CryptoInfo sCryptoInfo = new MediaCodec.CryptoInfo();

    public boolean writeSample(final MediaExtractor extractor,
            final boolean isSecure,
            final long presentationTimeUs,
            int flags) {
        boolean result = false;

        if (!mAvailableInputBuffers.isEmpty()) {
            int index = mAvailableInputBuffers.remove();
            ByteBuffer buffer = mInputBuffers[index];

            // reads the sample from the file using extractor into the buffer
            int size = extractor.readSampleData(buffer, 0);
            if (size <= 0) {
                flags |= MediaCodec.BUFFER_FLAG_END_OF_STREAM;
            }

            // Submit the buffer to the codec for decoding. The presentationTimeUs
            // indicates the position (play time) for the current sample.
            if (!isSecure) {
                mDecoder.queueInputBuffer(index, 0, size, presentationTimeUs, flags);
            } else {
                extractor.getSampleCryptoInfo(sCryptoInfo);
                mDecoder.queueSecureInputBuffer(index, 0, sCryptoInfo, presentationTimeUs, flags);
            }

            result = true;
        }
        return result;
    }

    public boolean peekSample(MediaCodec.BufferInfo out_bufferInfo) {
        // dequeue available buffers and synchronize our data structures with the codec.
        update();
        boolean result = false;
        if (!mAvailableOutputBuffers.isEmpty()) {
            int index = mAvailableOutputBuffers.peek();
            MediaCodec.BufferInfo info = mOutputBufferInfo[index];
            // metadata of the sample
            out_bufferInfo.set(
                    info.offset,
                    info.size,
                    info.presentationTimeUs,
                    info.flags);
            result = true;
        }
        return result;
    }

    
    public void popSample(boolean render) {
        // dequeue available buffers and synchronize our data structures with the codec.
        update();
        if (!mAvailableOutputBuffers.isEmpty()) {
            int index = mAvailableOutputBuffers.remove();

            // releases the buffer back to the codec
            mDecoder.releaseOutputBuffer(index, render);
        }
    }

    private void update() {
        // BEGIN_INCLUDE(update_codec_state)
        int index;

        
        while ((index = mDecoder.dequeueInputBuffer(0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
            mAvailableInputBuffers.add(index);
        }


        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while ((index = mDecoder.dequeueOutputBuffer(info, 0)) !=  MediaCodec.INFO_TRY_AGAIN_LATER) {
            switch (index) {
                case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                    mOutputBuffers = mDecoder.getOutputBuffers();
                    mOutputBufferInfo = new MediaCodec.BufferInfo[mOutputBuffers.length];
                    mAvailableOutputBuffers.clear();
                    break;
                case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                    if (mOutputFormatChangedListener != null) {
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                mOutputFormatChangedListener
                                        .outputFormatChanged(MediaCodecWrapper.this,
                                                mDecoder.getOutputFormat());

                            }
                        });
                    }
                    break;
                default:
                    
                    if (index >= 0) {
                        mOutputBufferInfo[index] = info;
                        mAvailableOutputBuffers.add(index);
                    } else {
                        throw new IllegalStateException("Unknown status from dequeueOutputBuffer");
                    }
                    break;
            }

        }
        // END_INCLUDE(update_codec_state)

    }

    private class WriteException extends Throwable {
        private WriteException(final String detailMessage) {
            super(detailMessage);
        }
    }
}
