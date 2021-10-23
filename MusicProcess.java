package com.maniu.musicclip;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

//MP3-->MP31
public class MusicProcess {

    @SuppressLint("WrongConstant")
    public void clip(String musicPath, String outPath, int startTime, int endTime) throws Exception {
        if (endTime < startTime) {
            return;
        }
//    MP3  （zip  rar    ） ----> aac   封装个事 1   编码格式
//        jie  MediaExtractor = 360 解压 工具
        MediaExtractor mediaExtractor = new MediaExtractor();

        mediaExtractor.setDataSource(musicPath);
        int audioTrack =selectTrack(mediaExtractor );

        mediaExtractor.selectTrack(audioTrack);
// 视频 和音频
        mediaExtractor.seekTo(startTime, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
// 轨道信息  都记录 编码器
        MediaFormat oriAudioFormat = mediaExtractor.getTrackFormat(audioTrack);
        int maxBufferSize = 100 * 1000;
        if (oriAudioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            maxBufferSize = oriAudioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
        } else {
            maxBufferSize = 100 * 1000;
        }


        ByteBuffer buffer = ByteBuffer.allocateDirect(maxBufferSize);

//        h264   H265
        MediaCodec mediaCodec = MediaCodec.createDecoderByType(oriAudioFormat.getString((MediaFormat.KEY_MIME)));
//        设置解码器信息    直接从 音频文件
        mediaCodec.configure(oriAudioFormat, null, null, 0);
        File pcmFile = new File(Environment.getExternalStorageDirectory(), "out.pcm");
        FileChannel writeChannel = new FileOutputStream(pcmFile).getChannel();
        mediaCodec.start();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int outputBufferIndex = -1;
        while (true) {
            int decodeInputIndex = mediaCodec.dequeueInputBuffer(100000);
            if (decodeInputIndex >= 0) {
                long sampleTimeUs = mediaExtractor.getSampleTime();

                if (sampleTimeUs == -1) {
                    break;
                } else if (sampleTimeUs < startTime) {
//                    丢掉 不用了
                    mediaExtractor.advance();
                    continue;
                }else if (sampleTimeUs > endTime) {
                    break;
                }
//                获取到压缩数据
                info.size = mediaExtractor.readSampleData(buffer, 0);
                info.presentationTimeUs = sampleTimeUs;
                info.flags = mediaExtractor.getSampleFlags();

//                下面放数据  到dsp解码
                byte[] content = new byte[buffer.remaining()];
                buffer.get(content);
//                输出文件  方便查看
//                FileUtils.writeContent(content);
//                解码
                ByteBuffer inputBuffer = mediaCodec.getInputBuffer(decodeInputIndex);
                inputBuffer.put(content);
                mediaCodec.queueInputBuffer(decodeInputIndex, 0, info.size, info.presentationTimeUs, info.flags);
//                释放上一帧的压缩数据
                mediaExtractor.advance();
            }

            outputBufferIndex = mediaCodec.dequeueOutputBuffer(info, 100_000);
            while (outputBufferIndex>=0) {
                ByteBuffer decodeOutputBuffer = mediaCodec.getOutputBuffer(outputBufferIndex);
                writeChannel.write(decodeOutputBuffer);//MP3  1   pcm2
                mediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                outputBufferIndex = mediaCodec.dequeueOutputBuffer(info, 100_000);
            }
        }
        writeChannel.close();
        mediaExtractor.release();
        mediaCodec.stop();
        mediaCodec.release();
//        转换MP3    pcm数据转换成mp3封装格式

        File wavFile = new File(Environment.getExternalStorageDirectory(),"output.mp3" );
        new PcmToWavUtil(44100,  AudioFormat.CHANNEL_IN_STEREO,
                2, AudioFormat.ENCODING_PCM_16BIT).pcmToWav(pcmFile.getAbsolutePath()
                , wavFile.getAbsolutePath());
        Log.i("David", "mixAudioTrack: 转换完毕");

    }
    private int selectTrack(MediaExtractor mediaExtractor) {
//获取每条轨道
        int numTracks = mediaExtractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
//            数据      MediaFormat
            MediaFormat format =mediaExtractor.getTrackFormat(i);
            String mime =   format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;



    }
}
