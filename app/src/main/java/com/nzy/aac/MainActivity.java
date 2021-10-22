package com.nzy.aac;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.media.AudioFormat;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 裁剪 mp3 ，
 */
public class MainActivity extends AppCompatActivity {

    private String musicPath;
    private String musicPcmPath;
    private MusicProcess musicProcess;
    private String outMp3Path;
    private TextView mTvProgress;
    private static final long STAR_TTIME = 10 * 1000 * 1000;
    private static final long END_TIME = 15 * 1000 * 1000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mTvProgress = findViewById(R.id.tv_progress);
        checkPermission();
        // 降噪 也就是 把 异常的波形 去除就可以了
        // 噪音 和 正常的 波形 去叠加。然后记录 所有的噪音 （固定的已知的），然后去除

        /**
         * 截取音频，不能直接截取aac ，需要截取 pcm ，然后截取
         */
        musicProcess = new MusicProcess();


    }

    public boolean checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && checkSelfPermission(
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, 1);

        }
        return false;
    }

    public void start(View view) {
        File file = new File(Environment.getExternalStorageDirectory(), "AAC");
        if (!file.exists()) {
            file.mkdir();
        }
        File fileMusic = new File(file, "music.mp3");
        if (fileMusic.exists()) {
            fileMusic.delete();
        }
        File filePcm = new File(file, "out.pcm");
        if (filePcm.exists()) {
            filePcm.delete();
        }
        File fileOutMp3 = new File(file, "out.mp3");
        if (fileOutMp3.exists()) {
            fileOutMp3.delete();
        }
        musicPath = fileMusic.getAbsolutePath();
        musicPcmPath = filePcm.getAbsolutePath();
        outMp3Path = fileOutMp3.getAbsolutePath();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    copyAssets("music.mp3", musicPath);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                try {
                    // 编码都是 微秒
                    MusicProcess.music2Pcm(musicPath, musicPcmPath, STAR_TTIME, END_TIME, new MusicProcess.CallBack() {
                        @Override
                        public void progress(long progress) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    long sum = END_TIME - STAR_TTIME;
                                    long num = progress - STAR_TTIME;

                                    long percent = num * 100 / sum;
                                    String content = "进度是：" + percent + "%";
                                    if (percent >= 100) {
                                        content = "进度是：转换完成";
                                    }

                                    mTvProgress.setText(content);

                                }
                            });
                        }
                    });


                    play();

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void play() {
        // pcm数据转换成mp3封装格式
        new PcmToWavUtil(44100, AudioFormat.CHANNEL_IN_STEREO,
                2, AudioFormat.ENCODING_PCM_16BIT).pcmToWav(musicPcmPath, outMp3Path);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                MediaPlayer player = new MediaPlayer();
                try {
                    player.setDataSource(outMp3Path);
                    player.prepare();
                    player.start();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
    }


    private void copyAssets(String assetsName, String path) throws IOException {
        AssetFileDescriptor assetFileDescriptor = getAssets().openFd(assetsName);
        FileChannel from = new FileInputStream(assetFileDescriptor.getFileDescriptor()).getChannel();
        FileChannel to = new FileOutputStream(path).getChannel();
        from.transferTo(assetFileDescriptor.getStartOffset(), assetFileDescriptor.getLength(), to);
    }
}