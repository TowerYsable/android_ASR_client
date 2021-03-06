package com.myhome.application;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class MainActivity extends AppCompatActivity{
//  ASR 参数
  private final int MY_PERMISSIONS_RECORD_AUDIO = 1;
  private static final String LOG_TAG = "voice";  // 提示voice信息
  private static final int SAMPLE_RATE = 16000;
  private static final int MAX_QUEUE_SIZE = 2500;  // 100 seconds audio, 1 / 0.04 * 100
  private static final int MAX_AUDIO_DURATION_MS = 50000;   //最长等待时间ms
  private boolean startRecord = false;  //是否开始录音
  private AudioRecord record = null;
  private int miniBufferSize = 0;  // 1280 bytes 648 byte 40ms, 0.04s
  private final BlockingQueue<short[]> bufferQueue = new ArrayBlockingQueue<>(MAX_QUEUE_SIZE);
  private int timeMs = 0;
  private String recognize_text;

/*assetFilePath(文件位置，文件名)
    返回文件路径 -> model_file
* */
  public static String assetFilePath(Context context, String assetName) {
    File file = new File(context.getFilesDir(), assetName);
    if (file.exists() && file.length() > 0) {
      return file.getAbsolutePath();
    }
    try (InputStream is = context.getAssets().open(assetName)) {
      try (OutputStream os = new FileOutputStream(file)) {
        byte[] buffer = new byte[4 * 1024];
        int read;
        while ((read = is.read(buffer)) != -1) {
          os.write(buffer, 0, read);
        }
        os.flush();
      }
      return file.getAbsolutePath();
    } catch (IOException e) {
      Log.e(LOG_TAG, "Error process asset " + assetName + " to file path");
    }
    return null;
  }


//  ########################################
//  ASR
  @Override
  public void onRequestPermissionsResult(int requestCode,
                                         String permissions[], int[] grantResults) {
    if (requestCode == MY_PERMISSIONS_RECORD_AUDIO) {
      if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        Log.i(LOG_TAG, "record permission is granted");
        initRecoder();
      } else {
        Toast.makeText(this, "Permissions denied to record audio", Toast.LENGTH_LONG).show();
        Button button = findViewById(R.id.button);
        button.setEnabled(false);
      }
    }
  }
//  oncreate
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    requestAudioPermissions();

    final String modelPath = new File(assetFilePath(this, "final.zip")).getAbsolutePath();
    final String dictPath = new File(assetFilePath(this, "words.txt")).getAbsolutePath();
    Recognize.init(modelPath, dictPath);

    Button button = findViewById(R.id.button);
    button.setText("开始录音");
    button.setOnClickListener(view -> {
      if (!startRecord) {
        startRecord = true;
        timeMs = 0;
        Recognize.reset();
        startRecordThread();
        startAsrThread();
        Recognize.startDecode();
        button.setText("结束录音");
        button.setEnabled(false);
      } else {
        startRecord = false;
        Recognize.setInputFinished();
        button.setText("开始录音");
        button.setEnabled(false);
      }
    });
  }

  private void requestAudioPermissions() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(this,
              new String[]{Manifest.permission.RECORD_AUDIO},
              MY_PERMISSIONS_RECORD_AUDIO);
    } else {
      initRecoder();
    }
  }

  private void initRecoder() {
    // buffer size in bytes 1280
    miniBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT);
    if (miniBufferSize == AudioRecord.ERROR || miniBufferSize == AudioRecord.ERROR_BAD_VALUE) {
      Log.e(LOG_TAG, "Audio buffer can't initialize!");
      return;
    }
    record = new AudioRecord(MediaRecorder.AudioSource.DEFAULT,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            miniBufferSize);
    if (record.getState() != AudioRecord.STATE_INITIALIZED) {
      Log.e(LOG_TAG, "Audio Record can't initialize!");
      return;
    }
    Log.i(LOG_TAG, "Record init okay");
  }

  private void startRecordThread() {
    new Thread(() -> {
      record.startRecording();
      Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
      while (startRecord) {
        short[] buffer = new short[miniBufferSize / 2];
        int read = record.read(buffer, 0, buffer.length);
        try {
          if (AudioRecord.ERROR_INVALID_OPERATION != read) {
            bufferQueue.put(buffer);
          }
        } catch (InterruptedException e) {
          Log.e(LOG_TAG, e.getMessage());
        }
        timeMs += read * 1000 / SAMPLE_RATE;
        Button button = findViewById(R.id.button);
        if (timeMs >= 200 && !button.isEnabled() && startRecord) {
          runOnUiThread(() -> button.setEnabled(true));
        }
        if (timeMs >= MAX_AUDIO_DURATION_MS) {
          startRecord = false;
          Recognize.setInputFinished();
          runOnUiThread(() -> {
            Toast.makeText(MainActivity.this,
                    String.format("Max audio duration is %d seconds", MAX_AUDIO_DURATION_MS / 1000),
                    Toast.LENGTH_LONG).show();
            button.setText("开始录音");
            button.setEnabled(false);
          });
        }
      }
      record.stop();
    }).start();
  }


  private void startAsrThread() {
    new Thread(() -> {
      // Send all data
      while (startRecord || bufferQueue.size() > 0) {
        try {
          short[] data = bufferQueue.take();
          Recognize.acceptWaveform(data);
        } catch (InterruptedException e) {
          Log.e(LOG_TAG, e.getMessage());
        }
      }

      // Wait for final result
      while (true) {
        // get result
        if (!Recognize.getFinished()) {
          runOnUiThread(() -> {
            recognize_text = Recognize.getResult();
          });
        } else {
          runOnUiThread(() -> {
            Button button = findViewById(R.id.button);
            button.setEnabled(true);
            EditText editText = findViewById(R.id.edittext_chatbox);
            editText.setText(recognize_text);
          });
          break;
        }
      }
    }).start();
  }

}