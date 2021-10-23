package com.nzy.aac;

import android.media.AudioFormat;
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
     *
     * @param musicPath
     * @param musicPcmPath
     * @param startTime
     * @param endTime
     * @param callBack
     * @throws IOException
     */
    public static void videoOrAudio2Pcm(String musicPath, String musicPcmPath, long startTime, long endTime, CallBack callBack) throws IOException {
        Log.i(TAG, "转换开始");

        MediaMetadataRetriever mediaMetadataRetriever = new MediaMetadataRetriever();
        mediaMetadataRetriever.setDataSource(musicPath);
        // 读取音视频时间
        int aacDurationMs = Integer.parseInt(mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        mediaMetadataRetriever.release();




        // 解封装 封装格式 mp3 ,mp3 封装着 aac
        MediaExtractor mediaExtractor = new MediaExtractor();
        // 设置文件路径
        mediaExtractor.setDataSource(musicPath);


        // 音频流 轨道的index
        int audioTrack = selectTrack(mediaExtractor, true);
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
                if (callBack != null) {
                    callBack.progress(sampleTime);
                }
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

    private static int selectTrack(MediaExtractor mediaExtractor, boolean isAudio) {
        // 轨道数量，就是有多少个文件 比如一个zip里面有很多文件
        // 每个轨道都有配置信息
        int trackCount = mediaExtractor.getTrackCount();
        for (int i = 0; i < trackCount; i++) {
            // 就是一个配置信息
            MediaFormat format = mediaExtractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            // 如果是 video/ 开头 那就是视频
            // 如果是音频
            if (isAudio) {
                if (mime.startsWith("audio/")) {
                    return i;
                }
            } else {
                if (mime.startsWith("video/")) {
                    return i;
                }
            }
        }
        return -1;
    }


    public interface CallBack {
        void progress(long progress);
    }


    public static void mixVideoAndMusic(String videoInput, File wavFile, String output, Integer startTimeUs, Integer endTimeUs) throws IOException {
        // mp4 ,输出到一个文件
        MediaMuxer mediaMuxer = new MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);


        MediaExtractor mediaExtractor = new MediaExtractor();
        mediaExtractor.setDataSource(videoInput);




        // 拿出原视频的音频轨道
        int audioIndex = selectTrack(mediaExtractor, true);

        //////////////////原视频的音频配置信息////////////////////////////////////////
        // 取出来音频配置信息
        MediaFormat audioFormat = mediaExtractor.getTrackFormat(audioIndex);
        // 最大帧率
        int audioBitrate = audioFormat.getInteger(MediaFormat.KEY_BIT_RATE);
        // 设置格式
        audioFormat.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AAC);

        // 开辟了一个轨道，空的轨道，只有写入数据才能用
        int muxerAudioIndex = mediaMuxer.addTrack(audioFormat);

        // 把 压缩格式 封装成 一个容器 mp4，mp3
        mediaMuxer.start();


        ////////////////////////处理wavFile///////////////////////////
        // --------- 处理传过来的音频文件 wavFile
        MediaExtractor pcmExtractor = new MediaExtractor();
        pcmExtractor.setDataSource(wavFile.getAbsolutePath());
        int audioTrack = selectTrack(pcmExtractor, true);
        //todo 把 wav 编码成 aac，PcmToWavUtil的 pcmToWav 其实是个wav，但是可以写成 .mp3
        pcmExtractor.selectTrack(audioTrack);
        // 拿到 wavfile的配置信息
        MediaFormat pcmTrackFormat = pcmExtractor.getTrackFormat(audioTrack);

        int maxBufferSize = 100 * 1000;
        // 如果原视频中的音频有最大size ，就设置给 wav 配置的最大size，也就是
        if (pcmTrackFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            maxBufferSize = pcmTrackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
        }

        // 采样率 声道数
        MediaFormat encodeFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2);
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
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        // 音频也是有 pts 每一帧的时间前后（不是长短）
        boolean enCodeDone = false;
        while (!enCodeDone) {
            int inputBufferIndex = encoder.dequeueInputBuffer(10_000);
            if (inputBufferIndex >= 0) {
                long samleTime = pcmExtractor.getSampleTime();
                if (samleTime < 0) {
                    // 编码到末尾时
                    encoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                } else {
                    int flag = pcmExtractor.getSampleFlags();
                    // 读取数据
                    int size = pcmExtractor.readSampleData(buffer, 0);


                    // DPS 返回来的 ByteBuffer ，千万别用
                    ByteBuffer inputBuffer = encoder.getInputBuffer(inputBufferIndex);
                    inputBuffer.clear();
                    inputBuffer.put(buffer);
                    inputBuffer.position(0);
//

                    encoder.queueInputBuffer(inputBufferIndex, 0, size, samleTime, flag);
                    pcmExtractor.advance();


                }
            }

            // todo 获取编码完之后的数据
            // 解码
            int outputBufferIndex = encoder.dequeueOutputBuffer(info, 100_000);
            while (outputBufferIndex >= 0) {
                // 读完了
                if (info.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                    enCodeDone = true;
                    break;
                }
                ByteBuffer encodeOutputBuffer = encoder.getOutputBuffer(outputBufferIndex);
                mediaMuxer.writeSampleData(muxerAudioIndex, encodeOutputBuffer, info);
                encodeOutputBuffer.clear();
                encoder.releaseOutputBuffer(outputBufferIndex, false);
                outputBufferIndex = encoder.dequeueOutputBuffer(info, 100_000);
            }
        }
        // 音频添加好了
        if (audioTrack >= 0) {
            mediaExtractor.unselectTrack(audioTrack);
        }


        ////////////////////处理视频添加的轨道////////////////////////


        // 获取视频轨道
        int videoIndex = selectTrack(mediaExtractor, false);
        // 视频的配置信息
        MediaFormat videoFormat = mediaExtractor.getTrackFormat(videoIndex);
        // 开辟了一个视频轨道，空的轨道，只有写入数据才能用
        mediaMuxer.addTrack(videoFormat);
        // 选择 视频的轨道
        mediaExtractor.selectTrack(videoIndex);
        // seekto到对应位置
        mediaExtractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        maxBufferSize = videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
        buffer = ByteBuffer.allocateDirect(maxBufferSize);

        while (true) {
            long sampleTimeUs = mediaExtractor.getSampleTime();
            if (sampleTimeUs == -1) {
                break;
            }
            if (sampleTimeUs < startTimeUs) {
                mediaExtractor.advance();
                continue;
            }
            if (endTimeUs != null && sampleTimeUs > endTimeUs) {
                break;
            }
            //  任何一个视频的第一个pts 都不是0 ，所以加点偏移量
            info.presentationTimeUs = sampleTimeUs - startTimeUs + 600;

            info.flags = mediaExtractor.getSampleFlags();
            // 读取视频文件的数据
            info.size = mediaExtractor.readSampleData(buffer, 0);
            if (info.size < 0) {
                break;
            }
            // 写入视频轨道 ----- 自己添加的轨道
            mediaMuxer.writeSampleData(videoIndex, buffer, info);
            mediaExtractor.advance();
        }

        try {
            pcmExtractor.release();
            mediaExtractor.release();
            encoder.stop();
            encoder.release();
            mediaMuxer.release();
        } catch (Exception e) {
        }


    }

    /**
     * * 给mp4 添加背景音乐
     * * 1. 拿到背景音乐的 pcm
     * * 2. 拿到 mp4 中的 pcm
     * * 3. 合并两个pcm
     * * 4. 然后把 pcm 转成 wav（mp3）
     * * 5. 然后把 wav 压缩成 aac
     * * 6. 放到视频中
     *
     * @param videoInput  输入视频路径
     * @param audioInput  mp3 路径
     * @param videoOutput 输出视频的路径
     * @param startTimeUs 开始时间
     * @param endTimeUs   结束时间
     * @param videoVolume 合成后 原视频的 音频大小
     * @param aacVolume   合成后 mp3的音频大小
     */
    public static void mixAudioTrack(final String videoInput,
                                     final String audioInput,
                                     final String videoOutput,
                                     final Integer startTimeUs, final Integer endTimeUs,
                                     int videoVolume,
                                     int aacVolume) throws IOException {
        File cacheDir = new File(Environment.getExternalStorageDirectory(), "AAC");
        // 1. 下载下来的音乐转换城pcm
        File aacPcmFile = new File(cacheDir, "audio" + ".pcm");
        // 2.  视频自带的音乐转换城pcm
        File videoPcmFile = new File(cacheDir, "video" + ".pcm");

        // MediaExtractor 音视频的 解封装
        MediaExtractor audioExtractor = new MediaExtractor();
        // 设置音频路径
        audioExtractor.setDataSource(audioInput);

        // 把 音频的pcm 保存到 aacPcmFile文件夹中
        videoOrAudio2Pcm(audioInput, aacPcmFile.getAbsolutePath(), startTimeUs, endTimeUs, null);
        // 把 视频的pcm 保存到 videoPcmFile 文件夹中
        videoOrAudio2Pcm(videoInput, videoPcmFile.getAbsolutePath(), startTimeUs, endTimeUs, null);

        // 3. 这是混合后的 pcm 文件
        File adjustedPcm = new File(cacheDir, "混合后的" + ".pcm");

        // 混合两个pcm 到 adjustedPcm文件
        FileUtils.mixPcm(videoPcmFile.getAbsolutePath(), aacPcmFile.getAbsolutePath(), adjustedPcm.getAbsolutePath(), videoVolume, aacVolume);

        // 4. 然后把 混合后的 pcm 弄成 wav
        File wavFile = new File(cacheDir, adjustedPcm.getName() + ".wav");
        new PcmToWavUtil(44100, AudioFormat.CHANNEL_IN_STEREO,
                2, AudioFormat.ENCODING_PCM_16BIT).pcmToWav(adjustedPcm.getAbsolutePath()
                , wavFile.getAbsolutePath());

        File mp3 = new File(cacheDir, "混合后的" + ".mp3");
        new PcmToWavUtil(44100, AudioFormat.CHANNEL_IN_STEREO,
                2, AudioFormat.ENCODING_PCM_16BIT).pcmToWav(adjustedPcm.getAbsolutePath()
                , mp3.getAbsolutePath());

        // 5. 把 合成好的 wav 给 视频文件
        mixVideoAndMusic(videoInput, wavFile, videoOutput, startTimeUs, endTimeUs);
    }


    /**
     * 两个视频拼接
     * @param inputPath1
     * @param inputPath2
     * @param outputPath
     * @return
     * @throws IOException
     */
    public static boolean appendVideo(String inputPath1, String inputPath2, String outputPath) throws IOException {
        MediaMuxer mediaMuxer = new MediaMuxer(outputPath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        // 第一个视频的 MediaExtractor
        MediaExtractor videoExtractor1 = new MediaExtractor();
        videoExtractor1.setDataSource(inputPath1);

        // 第二个视频的
        MediaExtractor videoExtractor2 = new MediaExtractor();
        videoExtractor2.setDataSource(inputPath2);

        int videoTrackIndex = -1;
        int audioTrackIndex = -1;
        // 记录第一个视频的总时长，到时候 第二个视频写入的时候要加上这个
        long file1_duration = 0L;

        // 第一个视频的index
        int sourceVideoTrack1 = -1;
        // 第一个音频的index
        int sourceAudioTrack1 = -1;
        for (int index = 0; index < videoExtractor1.getTrackCount(); index++) {
            MediaFormat format = videoExtractor1.getTrackFormat(index);
            String mime = format.getString(MediaFormat.KEY_MIME);
            file1_duration = format.getLong(MediaFormat.KEY_DURATION);
            if (mime.startsWith("video/")) {
                sourceVideoTrack1 = index;
                videoTrackIndex = mediaMuxer.addTrack(format);
            } else if (mime.startsWith("audio/")) {
                sourceAudioTrack1 = index;
                audioTrackIndex = mediaMuxer.addTrack(format);
            }
        }

        int sourceVideoTrack2 = -1;
        int sourceAudioTrack2 = -1;
        for (int index = 0; index < videoExtractor2.getTrackCount(); index++) {
            MediaFormat format = videoExtractor2.getTrackFormat(index);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("video/")) {
                sourceVideoTrack2 = index;
            } else if (mime.startsWith("audio/")) {
                sourceAudioTrack2 = index;
            }
        }

        if (mediaMuxer == null)
            return false;

        mediaMuxer.start();

        //1. 写入 video
        videoExtractor1.selectTrack(sourceVideoTrack1);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        info.presentationTimeUs = 0;
        ByteBuffer buffer = ByteBuffer.allocate(500 * 1024);
        int sampleSize = 0;
        while ((sampleSize = videoExtractor1.readSampleData(buffer, 0)) > 0) {
            byte[] data = new byte[buffer.remaining()];

            buffer.get(data);
            FileUtils.writeBytes(data);
            FileUtils.writeContent(data);
            info.offset = 0;
            info.size = sampleSize;
            info.flags = videoExtractor1.getSampleFlags();
            info.presentationTimeUs = videoExtractor1.getSampleTime();
            mediaMuxer.writeSampleData(videoTrackIndex, buffer, info);
            videoExtractor1.advance();
        }
        videoExtractor1.unselectTrack(sourceVideoTrack1);

        //2. 写入 音频
        videoExtractor1.selectTrack(sourceAudioTrack1);
        info = new MediaCodec.BufferInfo();
        info.presentationTimeUs = 0;
        buffer = ByteBuffer.allocate(500 * 1024);
        sampleSize = 0;
        while ((sampleSize = videoExtractor1.readSampleData(buffer, 0)) > 0) {
            info.offset = 0;
            info.size = sampleSize;
            info.flags = videoExtractor1.getSampleFlags();
            info.presentationTimeUs = videoExtractor1.getSampleTime();
            videoExtractor1.advance();
        }

        //3.
        videoExtractor2.selectTrack(sourceVideoTrack2);
        info = new MediaCodec.BufferInfo();
        info.presentationTimeUs = 0;
        buffer = ByteBuffer.allocate(500 * 1024);
        sampleSize = 0;
        while ((sampleSize = videoExtractor2.readSampleData(buffer, 0)) > 0) {
            info.offset = 0;
            info.size = sampleSize;
            info.flags = videoExtractor2.getSampleFlags();
            info.presentationTimeUs = videoExtractor2.getSampleTime() + file1_duration;
            mediaMuxer.writeSampleData(videoTrackIndex, buffer, info);
            videoExtractor2.advance();
        }

        //4.write second audio track into muxer.
        videoExtractor2.unselectTrack(sourceVideoTrack2);
        videoExtractor2.selectTrack(sourceAudioTrack2);
        info = new MediaCodec.BufferInfo();
        info.presentationTimeUs = 0;
        buffer = ByteBuffer.allocate(500 * 1024);
        sampleSize = 0;
        while ((sampleSize = videoExtractor2.readSampleData(buffer, 0)) > 0) {
            info.offset = 0;
            info.size = sampleSize;
            info.flags = videoExtractor2.getSampleFlags();
            info.presentationTimeUs = videoExtractor2.getSampleTime() + file1_duration;
            mediaMuxer.writeSampleData(audioTrackIndex, buffer, info);
            videoExtractor2.advance();
        }

        videoExtractor1.release();
        videoExtractor2.release();
        mediaMuxer.stop();
        mediaMuxer.release();

        return true;
    }
}
