package com.nzy.aac;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * @author niezhiyang
 * since 10/20/21
 */
public class MusicProcess {
    private static final String TAG = "MusicProcess";


    public void clip(String musicPath, String musicPcmPath, String outMp3Path, long startTime, long endTime, CallBack callBack) throws IOException {
        Log.i(TAG, "转换开始");
        // 解封装 封装格式 mp3 ,mp3 封装着 aac
        MediaExtractor mediaExtractor = new MediaExtractor();
        // 设置文件路径
        mediaExtractor.setDataSource(musicPath);

        MediaMetadataRetriever mediaMetadataRetriever = new MediaMetadataRetriever();
        mediaMetadataRetriever.setDataSource(musicPath);
        //读取音乐时间
        final int aacDurationMs = Integer.parseInt(mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        mediaMetadataRetriever.release();

        // 音频流 轨道的index
        int audioTrack = selectTrack(mediaExtractor);
        // 定位到 这个音频轨道
        mediaExtractor.selectTrack(audioTrack);

        // 直接 seekto 到 startTime
        mediaExtractor.seekTo(startTime, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

        // 轨道信息
        MediaFormat trackFormat = mediaExtractor.getTrackFormat(audioTrack);

        // 100k
        int maxufferSize = 100 * 1000;
        // 有的音频有 ，有的音频没有
        if (trackFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            maxufferSize = trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
        }

        // 设置缓存大小
        ByteBuffer buffer = ByteBuffer.allocateDirect(maxufferSize);
        // 通过 MediaFormat.KEY_MIME 这个 key 拿到 编码格式
        MediaCodec mediaCodec = MediaCodec.createDecoderByType(trackFormat.getString(MediaFormat.KEY_MIME));
        // 因为需要把aac 转成 pcm 即是解码，flags = 0
        mediaCodec.configure(trackFormat, null, null, 0);

        // 把 mp3 解压的pcm 放到musicPcmFile文件中
        File musicPcmFile = new File(musicPcmPath);
        FileChannel writeChannel = new FileOutputStream(musicPcmFile).getChannel();
        // 开始解
        mediaCodec.start();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        while (true) {
            // 读取数据
            int index = mediaCodec.dequeueInputBuffer(100_000);
            if (index > 0) {

                // 拿到时间
                long sampleTime = mediaExtractor.getSampleTime();
                Log.e(TAG, index + "---" + sampleTime);
                callBack.progress(sampleTime);
                if (sampleTime == -1) {
                    //     结束
                    break;
                } else if (sampleTime < startTime) {
                    // 下一个数据 Advance to the next sample
                    mediaExtractor.advance();
                    continue;
                } else if (sampleTime > endTime) {
                    //     结束
                    break;
                }
                // 获取压缩数据 压缩数据大小
                // 读取数据到bytebuffer中，从offset偏移开始。选定轨道之后可以读取该轨道的数据。
                info.size = mediaExtractor.readSampleData(buffer, 0);
                info.presentationTimeUs = sampleTime;
                info.flags = mediaExtractor.getSampleFlags();


                byte[] content = new byte[buffer.remaining()];
                buffer.get(content);

                // 把这个写到txt 文件中中 以便于我们观察
                FileUtils.writeContent(content);

                // 解码
                ByteBuffer inputBuffer = mediaCodec.getInputBuffer(index);
                inputBuffer.put(content);
                mediaCodec.queueInputBuffer(index, 0, info.size, info.presentationTimeUs, info.flags);

                // 获取下一帧数据
                mediaExtractor.advance();
            }

            // 解码 拿出来输出数据
            int outputBufferIndex = mediaCodec.dequeueOutputBuffer(info, 100_000);
            while (outputBufferIndex >= 0) {
                ByteBuffer decodeOutputBuffer = mediaCodec.getOutputBuffer(outputBufferIndex);
                //写到 pcm 文件中
                writeChannel.write(decodeOutputBuffer);
                // 释放
                mediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                // 下一个数据
                outputBufferIndex = mediaCodec.dequeueOutputBuffer(info, 100_000);
            }

        }
        writeChannel.close();
        mediaExtractor.release();
        mediaCodec.stop();
        mediaCodec.release();


        // pcm数据转换成mp3封装格式

        new PcmToWavUtil(44100, AudioFormat.CHANNEL_IN_STEREO,
                2, AudioFormat.ENCODING_PCM_16BIT).pcmToWav(musicPcmFile.getAbsolutePath()
                , outMp3Path);
        Log.i(TAG, "转换完毕");


    }

    private int selectTrack(MediaExtractor mediaExtractor) {
        // 轨道数量，就是有多少个文件 比如一个zip里面有很多文件
        // 每个轨道都有配置信息
        int trackCount = mediaExtractor.getTrackCount();
        for (int i = 0; i < trackCount; i++) {
            // 就是一个配置信息
            MediaFormat format = mediaExtractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            // 如果是 video/ 开头 那就是视频
            // 如果是音频
            if (mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }


    public interface CallBack {
        void progress(long progress);
    }
}
