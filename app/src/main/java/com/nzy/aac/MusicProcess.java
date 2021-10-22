package com.nzy.aac;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.os.Environment;
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


    /**
     * 音频转成 pcm
     * @param musicPath
     * @param musicPcmPath
     * @param startTime
     * @param endTime
     * @param callBack
     * @throws IOException
     */
    public static void  music2Pcm(String musicPath, String musicPcmPath,  long startTime, long endTime, CallBack callBack) throws IOException {
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
        int audioTrack = selectTrack(mediaExtractor,true);
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





    }

    private static int selectTrack(MediaExtractor mediaExtractor,boolean isAudio) {
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


    public static void mixVideoAndMusic(File videoInput, String output, Integer startTimeUs, Integer endTimeUs, File wavFile) throws IOException {
        //           mp4 ,输出到一个文件
        MediaMuxer mediaMuxer = new MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        // 一个轨道 既可以装音频 也可以装视频
//        mediaMuxer.addTrack()


        //todo 一 先处理----------音频轨道-------------------
        MediaExtractor audioExtractor = new MediaExtractor();
        audioExtractor.setDataSource(videoInput.getAbsolutePath());

        // 取出来音频轨道
        int audioTrack = selectTrack(audioExtractor, true);
        // 定位到轨道
        audioExtractor.selectTrack(audioTrack);
        // 取出来音频配置信息
        MediaFormat audioFormat = audioExtractor.getTrackFormat(audioTrack);

        // 设置格式
        audioFormat.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AAC);

        // 开辟了一个轨道，空的轨道，只有写入数据才能用
        int muxerAudioIndex = mediaMuxer.addTrack(audioFormat);

        //todo --------- 开始工作
        // 把 压缩格式 封装成 一个容器 mp4，mp3
        mediaMuxer.start();
        // --------- 音视频 轨道已经开启


        //todo 把 wav 编码成 aac，PcmToWavUtil的 pcmToWav 其实是个wav，但是可以写成 .mp3
        int maxBufferSize = 100 * 1000;
        if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            maxBufferSize = audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
        }
        // 采样率 声道数
        MediaFormat encodeFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2);
        int audioBitrate = audioFormat.getInteger(MediaFormat.KEY_BIT_RATE);
        encodeFormat.setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate);
        // 编码等级，一办就行，越大越高
        encodeFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        encodeFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxBufferSize);

        // 编码成aac
        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        // 配置 aac参数 ，如果编解码器被用作编码器，传递这个标志。MediaCodec.CONFIGURE_FLAG_ENCODE
        encoder.configure(encodeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        // 容器
        ByteBuffer buffer = ByteBuffer.allocateDirect(maxBufferSize);
        // 音频也是有 pts 每一帧的时间前后（不是长短）
        boolean enCodeDone = true;
        while (enCodeDone) {
            int inputBufferIndex = encoder.dequeueInputBuffer(10_000);
            if (inputBufferIndex >= 0) {
                long samleTime = audioExtractor.getSampleTime();
                if (samleTime < 0) {
                    // 编码到末尾时
                    encoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                } else {
                    int flag = audioExtractor.getSampleFlags();
                    // 读取数据
                    int size = audioExtractor.readSampleData(buffer, 0);


                    // DPS 返回来的 ByteBuffer ，千万别用
                    ByteBuffer inputBuffer = encoder.getInputBuffer(inputBufferIndex);
                    inputBuffer.clear();
                    inputBuffer.put(buffer);
                    inputBuffer.position(0);
//

                    encoder.queueInputBuffer(inputBufferIndex, 0, size, samleTime, flag);
                    audioExtractor.advance();


                }
            }

            // todo 获取编码完之后的数据
            // 解码
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int outputBufferIndex = encoder.dequeueOutputBuffer(info, 100_000);
            while (outputBufferIndex >= 0) {
                // 读完了
                if (info.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                    enCodeDone = false;
                    break;
                }
                ByteBuffer encodeOutputBuffer = encoder.getOutputBuffer(outputBufferIndex);
                mediaMuxer.writeSampleData(muxerAudioIndex, encodeOutputBuffer, info);
                encodeOutputBuffer.clear();
                encoder.releaseOutputBuffer(outputBufferIndex, false);
                outputBufferIndex = encoder.dequeueOutputBuffer(info, 100_000);
            }
        }
        if (audioTrack >= 0) {
            audioExtractor.unselectTrack(audioTrack);
        }


        //todo 二 开始搞视频
        MediaExtractor videoExtractor = new MediaExtractor();
        videoExtractor.setDataSource(videoInput.getAbsolutePath());
        // 取出来视频轨道
        int videoTrack = selectTrack(videoExtractor, false);
        // 定位到轨道
        videoExtractor.selectTrack(videoTrack);
        // 取出来视频配置信息
        MediaFormat videoFormat = videoExtractor.getTrackFormat(videoTrack);

        // 开辟了一个轨道，空的轨道，只有写入数据才能用
        mediaMuxer.addTrack(videoFormat);

        videoExtractor.seekTo(videoTrack, MediaExtractor.SEEK_TO_CLOSEST_SYNC);


    }

    /**
     *      * 给mp4 添加背景音乐
     *      * 1. 拿到背景音乐的 pcm
     *      * 2. 拿到 mp4 的 aac 中 的 pcm
     *      * 3. 合并两个pcm
     *      * 4. 然后把 pcm 转成 wav（mp3）
     *      * 5. 然后把 wav 压缩成 aac
     *      * 6. 放到视频中
     * @param videoInput 输入视频路径
     * @param audioInput mp3 路径
     * @param videoOutput  输出视频的路径
     * @param startTimeUs  开始时间
     * @param endTimeUs    结束时间
     * @param videoVolume  合成后 原视频的 音频大小
     * @param aacVolume    合成后 mp3的音频大小
     */
    public static void mixAudioTrack(final String videoInput,
                                     final String audioInput,
                                     final String videoOutput,
                                     final Integer startTimeUs, final Integer endTimeUs,
                                     int videoVolume,
                                     int aacVolume) throws IOException {
        File cacheDir = Environment.getExternalStorageDirectory();
        //  下载下来的音乐转换城pcm
        File aacPcmFile = new File(cacheDir, "audio" + ".pcm");
        //  视频自带的音乐转换城pcm
        final File videoPcmFile = new File(cacheDir, "video" + ".pcm");

        // MediaExtractor 音视频的 解封装
        MediaExtractor audioExtractor = new MediaExtractor();
        // 设置音频路基
        audioExtractor.setDataSource(audioInput);

    }
}
