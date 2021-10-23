package com.nzy.aac;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

import androidx.appcompat.app.AppCompatActivity;

public class AppendVideoActivity extends AppCompatActivity {

    private VideoView videoView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_append_video);
        videoView = findViewById(R.id.videoView);
        checkPermission();
    }

    public void add(View view) {
        File cacheDir = new File(Environment.getExternalStorageDirectory(), "AAC");
        if (!cacheDir.exists()) {
            cacheDir.mkdir();
        }
        final String videoPath = new File(cacheDir, "input.mp4").getAbsolutePath();
        final String videoPath1 = new File(cacheDir, "input2.mp4").getAbsolutePath();
        final String outPath = new File(cacheDir, "outPath.mp4").getAbsolutePath();
        new Thread() {
            @Override
            public void run() {

                try {
                    copyAssets("input.mp4", videoPath);
                    copyAssets("input2.mp4", videoPath1);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                try {
                    MusicProcess.appendVideo(videoPath1, videoPath, outPath);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        startPlay(outPath);
                        Toast.makeText(AppendVideoActivity.this, "合并完成", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }.start();


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


    private void copyAssets(String assetsName, String path) throws IOException {
        AssetFileDescriptor assetFileDescriptor = getAssets().openFd(assetsName);
        FileChannel from = new FileInputStream(assetFileDescriptor.getFileDescriptor()).getChannel();
        FileChannel to = new FileOutputStream(path).getChannel();
        from.transferTo(assetFileDescriptor.getStartOffset(), assetFileDescriptor.getLength(), to);
    }

    private void startPlay(String path) {
        ViewGroup.LayoutParams layoutParams = videoView.getLayoutParams();
        layoutParams.height = 675;
        layoutParams.width = 1285;
        videoView.setLayoutParams(layoutParams);
        videoView.setVideoPath(path);

        videoView.start();
        videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {

            @Override
            public void onPrepared(MediaPlayer mp) {
            }
        });
    }
}