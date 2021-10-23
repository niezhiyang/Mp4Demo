package com.maniu.douyinsample;

import android.annotation.SuppressLint;
import android.content.Context;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class MusicProcess {
    private static final String TAG = "david";
    private static int TIMEOUT = 1000;

    public static void mixAudioTrack(Context context,
                                     final String videoInput,
                                     final String audioInput,
                                     final String output,
                                     final Integer startTimeUs, final Integer endTimeUs,
                                     int videoVolume,
                                     int aacVolume
    ) throws Exception {

        File cacheDir = Environment.getExternalStorageDirectory();
//        下载下来的音乐转换城pcm
        File aacPcmFile = new File(cacheDir, "audio" + ".pcm");
//        视频自带的音乐转换城pcm
        final File videoPcmFile = new File(cacheDir, "video" + ".pcm");

        MediaMetadataRetriever mediaMetadataRetriever = new MediaMetadataRetriever();
        mediaMetadataRetriever.setDataSource(audioInput);
//        读取音乐时间
        final int aacDurationMs = Integer.parseInt(mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        mediaMetadataRetriever.release();

        MediaExtractor audioExtractor = new MediaExtractor();
        audioExtractor.setDataSource(audioInput);

        decodeToPCM(videoInput, videoPcmFile.getAbsolutePath(),
                startTimeUs, endTimeUs);

        final int videoDurationMs = (endTimeUs - startTimeUs) / 1000;

        decodeToPCM(audioInput, aacPcmFile.getAbsolutePath(), startTimeUs, endTimeUs);

        File adjustedPcm = new File(cacheDir, "混合后的" + ".pcm");
        mixPcm(videoPcmFile.getAbsolutePath(), aacPcmFile.getAbsolutePath(),
                adjustedPcm.getAbsolutePath()
                , videoVolume, aacVolume);
        File wavFile = new File(cacheDir, adjustedPcm.getName() + ".wav");
        new PcmToWavUtil(44100, AudioFormat.CHANNEL_IN_STEREO,
                2, AudioFormat.ENCODING_PCM_16BIT).pcmToWav(adjustedPcm.getAbsolutePath()
                , wavFile.getAbsolutePath());
        Log.i(TAG, "mixAudioTrack: 转换完毕");
//混音的wav文件   + 视频文件   ---》  生成

        mixVideoAndMusic(videoInput, output, startTimeUs, endTimeUs, wavFile);

    }

    private static void mixVideoAndMusic(String videoInput, String output, Integer startTimeUs, Integer endTimeUs, File wavFile) throws IOException {


        //        初始化一个视频封装容器
        MediaMuxer mediaMuxer = new MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

//            一个轨道    既可以装音频 又视频   是 1 不是2
//            取音频轨道  wav文件取配置信息
//            先取视频
        MediaExtractor mediaExtractor = new MediaExtractor();
        mediaExtractor.setDataSource(videoInput);
//            拿到视频轨道的索引
        int videoIndex = selectTrack(mediaExtractor, false);

        int audioIndex = selectTrack(mediaExtractor, true);


//            视频配置 文件
        MediaFormat videoFormat = mediaExtractor.getTrackFormat(videoIndex);
//开辟了一个 轨道   空的轨道   写数据     真实
        mediaMuxer.addTrack(videoFormat);

//        ------------音频的数据已准备好----------------------------
//            视频中音频轨道   应该取自于原视频的音频参数
        MediaFormat audioFormat = mediaExtractor.getTrackFormat(audioIndex);
        int audioBitrate = audioFormat.getInteger(MediaFormat.KEY_BIT_RATE);
        audioFormat.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AAC);
        //        添加一个空的轨道  轨道格式取自 视频文件，跟视频所有信息一样
        int muxerAudioIndex = mediaMuxer.addTrack(audioFormat);


//            音频轨道开辟好了  输出开始工作
        mediaMuxer.start();

//音频的wav
        MediaExtractor pcmExtrator = new MediaExtractor();
        pcmExtrator.setDataSource(wavFile.getAbsolutePath());
        int audioTrack = selectTrack(pcmExtrator, true);
        pcmExtrator.selectTrack(audioTrack);
        MediaFormat pcmTrackFormat = pcmExtrator.getTrackFormat(audioTrack);


        //最大一帧的 大小
        int maxBufferSize = 0;
        if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            maxBufferSize = pcmTrackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
        } else {
            maxBufferSize = 100 * 1000;
        }


//    最终输出   后面   混音   -----》     重采样   混音     这个下节课讲
        MediaFormat encodeFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                44100, 2);//参数对应-> mime type、采样率、声道数
        encodeFormat.setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate);//比特率
//            音质等级
        encodeFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
//            解码  那段
        encodeFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxBufferSize);
//解码 那
        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
//            配置AAC 参数  编码 pcm   重新编码     视频文件变得更小
        encoder.configure(encodeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        encoder.start();
//            容器
        ByteBuffer buffer = ByteBuffer.allocateDirect(maxBufferSize);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean encodeDone = false;
        while (!encodeDone) {
            int inputBufferIndex = encoder.dequeueInputBuffer(10000);
            if (inputBufferIndex >= 0) {
                long sampleTime = pcmExtrator.getSampleTime();

                if (sampleTime < 0) {
//                        pts小于0  来到了文件末尾 通知编码器  不用编码了
                    encoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                } else {
                    int flags = pcmExtrator.getSampleFlags();
//
                    int size = pcmExtrator.readSampleData(buffer, 0);
//                    编辑     行 1 还是不行 2   不要去用  空的
                    ByteBuffer inputBuffer = encoder.getInputBuffer(inputBufferIndex);
                    inputBuffer.clear();
                    inputBuffer.put(buffer);
                    inputBuffer.position(0);

                    encoder.queueInputBuffer(inputBufferIndex, 0, size, sampleTime, flags);
//                        读完这一帧
                    pcmExtrator.advance();
                }
            }
//                获取编码完的数据
            int outputBufferIndex = encoder.dequeueOutputBuffer(info, TIMEOUT);
            while (outputBufferIndex >= 0) {
                if (info.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                    encodeDone = true;
                    break;
                }
                ByteBuffer encodeOutputBuffer = encoder.getOutputBuffer(outputBufferIndex);
//                    将编码好的数据  压缩 1     aac
                mediaMuxer.writeSampleData(muxerAudioIndex, encodeOutputBuffer, info);
                encodeOutputBuffer.clear();
                encoder.releaseOutputBuffer(outputBufferIndex, false);
                outputBufferIndex = encoder.dequeueOutputBuffer(info, TIMEOUT);
            }
        }
//    把音频添加好了
        if (audioTrack >= 0) {
            mediaExtractor.unselectTrack(audioTrack);
        }
//视频
        mediaExtractor.selectTrack(videoIndex);

        mediaExtractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        maxBufferSize = videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
        buffer = ByteBuffer.allocateDirect(maxBufferSize);
//封装容器添加视频轨道信息
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
//                pts      0
            info.presentationTimeUs = sampleTimeUs - startTimeUs + 600;
            info.flags = mediaExtractor.getSampleFlags();
//                读取视频文件的数据  画面 数据   压缩1  未压缩2
            info.size = mediaExtractor.readSampleData(buffer, 0);
            if (info.size < 0) {
                break;
            }
//                视频轨道  画面写完了
            mediaMuxer.writeSampleData(videoIndex, buffer, info);
            mediaExtractor.advance();
        }

        try {
            pcmExtrator.release();
            mediaExtractor.release();
            encoder.stop();
            encoder.release();
            mediaMuxer.release();
        } catch (Exception e) {
        }

    }

    private static float normalizeVolume(int volume) {
        return volume / 100f * 1;
    }

    public static void mixPcm(String pcm1Path, String pcm2Path, String toPath
            , int vol1, int vol2) throws IOException {
        float volume1 = normalizeVolume(vol1);
        float volume2 = normalizeVolume(vol2);
        byte[] buffer1 = new byte[2048];
        byte[] buffer2 = new byte[2048];
        byte[] buffer3 = new byte[2048];

        FileInputStream is1 = new FileInputStream(pcm1Path);
        FileInputStream is2 = new FileInputStream(pcm2Path);

        FileOutputStream fileOutputStream = new FileOutputStream(toPath);

        boolean end1 = false, end2 = false;
        short temp2, temp1;
        int temp;
        try {
            while (!end1 || !end2) {
                if (!end1) {
                    end1 = (is1.read(buffer1) == -1);

                    System.arraycopy(buffer1, 0, buffer3, 0, buffer1.length);
                }
                if (!end2) {
                    end2 = (is2.read(buffer2) == -1);
                    int voice = 0;
                    StringBuilder stringBuilder = new StringBuilder();
                    for (int i = 0; i < buffer2.length; i += 2) {
                        temp1 = (short) ((buffer1[i] & 0xff) | (buffer1[i + 1] & 0xff) << 8);
                        stringBuilder.append(temp1 + " ");
                        temp2 = (short) ((buffer2[i] & 0xff) | (buffer2[i + 1] & 0xff) << 8);
                        temp = (int) (temp2 * volume2 + temp1 * volume1);
                        if (temp > 32767) {
                            temp = 32767;
                        } else if (temp < -32768) {
                            temp = -32768;
                        }
                        buffer3[i] = (byte) (temp & 0xFF);
                        buffer3[i + 1] = (byte) ((temp >>> 8) & 0xFF);
                    }
                    Log.i(TAG, "mixPcm: " + stringBuilder.toString());
                }
                fileOutputStream.write(buffer3);
            }
        } finally {
            is1.close();
            is2.close();
            fileOutputStream.close();
        }
    }

    //    MP3 截取并且输出  pcm
    @SuppressLint("WrongConstant")
    public static void decodeToPCM(String musicPath, String outPath, int startTime, int endTime) throws Exception {
        if (endTime < startTime) {
            return;
        }
//    MP3  （zip  rar    ） ----> aac   封装个事 1   编码格式
//        jie  MediaExtractor = 360 解压 工具
        MediaExtractor mediaExtractor = new MediaExtractor();

        mediaExtractor.setDataSource(musicPath);
        int audioTrack = selectTrack(mediaExtractor, true);

        mediaExtractor.selectTrack(audioTrack);
// 视频 和音频
        mediaExtractor.seekTo(startTime, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
// 轨道信息  都记录 编码器
        MediaFormat audioFormat = mediaExtractor.getTrackFormat(audioTrack);
        int maxBufferSize = 100 * 1000;
        if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            maxBufferSize = audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
        } else {
            maxBufferSize = 100 * 1000;
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(maxBufferSize);
//        h264   H265  音频
        MediaCodec mediaCodec = MediaCodec.createDecoderByType(audioFormat
                .getString((MediaFormat.KEY_MIME)));
//        设置解码器信息    直接从 音频文件
        mediaCodec.configure(audioFormat, null, null, 0);
        File pcmFile = new File(outPath);
        FileChannel writeChannel = new FileOutputStream(pcmFile).getChannel();
        mediaCodec.start();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int outputBufferIndex = -1;
        while (true) {
            int decodeInputIndex = mediaCodec.dequeueInputBuffer(1000);
            if (decodeInputIndex >= 0) {
                long sampleTimeUs = mediaExtractor.getSampleTime();

                if (sampleTimeUs == -1) {
                    break;
                } else if (sampleTimeUs < startTime) {
//                    丢掉 不用了
                    mediaExtractor.advance();
                    continue;
                } else if (sampleTimeUs > endTime) {
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

            outputBufferIndex = mediaCodec.dequeueOutputBuffer(info, 1_000);
            while (outputBufferIndex >= 0) {
                ByteBuffer decodeOutputBuffer = mediaCodec.getOutputBuffer(outputBufferIndex);
                writeChannel.write(decodeOutputBuffer);//MP3  1   pcm2
                mediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                outputBufferIndex = mediaCodec.dequeueOutputBuffer(info, 1_000);
            }
        }
        writeChannel.close();
        mediaExtractor.release();
        mediaCodec.stop();
        mediaCodec.release();
//        转换MP3    pcm数据转换成mp3封装格式
//
//        File wavFile = new File(Environment.getExternalStorageDirectory(),"output.mp3" );
//        new PcmToWavUtil(44100,  AudioFormat.CHANNEL_IN_STEREO,
//                2, AudioFormat.ENCODING_PCM_16BIT).pcmToWav(pcmFile.getAbsolutePath()
//                , wavFile.getAbsolutePath());
        Log.i("David", "mixAudioTrack: 转换完毕");
    }

    public static int selectTrack(MediaExtractor extractor, boolean audio) {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (audio) {
                if (mime.startsWith("audio/")) {
                    return i;
                }
            } else {
                if (mime.startsWith("video/")) {
                    return i;
                }
            }
        }
        return -5;
    }
}
