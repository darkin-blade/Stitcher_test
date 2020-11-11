package com.example.my_stitcher;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements DialogInterface.OnDismissListener {
    static String appPath;

    ImageView my_result, opencv_result;
    static LinearLayout photos;
    Button button_save, button_camera, button_delete;
    Button button_my, button_opencv;
    View my_progress, opencv_progress;
//    public TextView stitch_log;

    /* 线程管理 */
    static final int MODE_MY = 1;
    static final int MODE_OPENCV = 2;
    int[] threadCount = new int[3];

    /* 系统相机功能 */
    public static final int PERMISSION_CAMERA_REQUEST_CODE = 0x00000012;// 相机权限的 request code
    Uri photoUri = null;
    String photoPath = null;

    ArrayList<Bitmap> photo_list = new ArrayList<>();// 图片list
    ArrayList<String> photo_name = new ArrayList<>();// 图片地址list
    ArrayList<Double> photo_rotation = new ArrayList<>();// 图片旋转角度
    ArrayList<Integer> photo_selected = new ArrayList<>();
    ArrayList<Integer> pairFirst = new ArrayList<>();
    ArrayList<Integer> pairSecond = new ArrayList<>();

    /* 保存拼接结果 */
    Bitmap my_bmp, opencv_bmp;

    // 从jni更新UI
    static MainHandler mainHandler;

    // 相机部件
    CustomCamera3 customCamera = new CustomCamera3();

    // 初始化opencv java
    static {
        if (!OpenCVLoader.initDebug()) {
            infoLog("opencv init failed");
        } else {
            infoLog("opencv init succeed");
        }
    }

    // Used to load the 'native-lib' library on application startup.
    static {
        System.loadLibrary("entry");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        appPath = getExternalFilesDir("").getAbsolutePath();

        initUI();
        initApp();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        // 权限处理回调
        if (requestCode == PERMISSION_CAMERA_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 允许权限
                addToLog("camera is ready");
            } else {
                // 权限被拒绝
                addToLog("get camera permission failed");
            }
        }
    }

    // 接收系统拍摄的相片
    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PERMISSION_CAMERA_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                addToLog("get photo [" + photoPath + "]");
                addPhoto(photoPath);// 直接根据路径添加图片
//                if (Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
//                    // Android 10 以上F
//                } else {
//                    // Android 10 以下
//                    try {
//                        Bitmap bitmap = BitmapFactory.decodeStream(getContentResolver().openInputStream(photoUri));
//                        addPhoto(photoPath);
//                    } catch (FileNotFoundException e) {
//                        e.printStackTrace();
//                    }
//                }
            } else {
                addToLog("canceled");
            }
        }
    }

    @Override
    public void onDismiss(DialogInterface dialogInterface) {
        infoLog("dismiss");
        if (customCamera.dismiss_result == 0) {
            // 返回
            return;
        }

        // 删除所有照片及ImageView
        int view_count = photos.getChildCount();
        for (int i = 0; i < view_count; i ++) {
            deletePhoto(0);
            photos.removeViewAt(0);
        }

        infoLog("photo num: " + customCamera.photo_name.size() + "/" + customCamera.photo_num);
        for (int i = 0; i < customCamera.photo_name.size(); i ++) {
            addPhoto(customCamera.photo_name.get(i));
        }

        // 获取相机旋转角度
        photo_rotation.clear();// 先清空
        for (int i = 0; i < customCamera.photo_rotation.size(); i ++) {
            double tmp_rotation = customCamera.photo_rotation.get(i).get(2);// 获取屏幕角度
            photo_rotation.add(tmp_rotation);
        }

        // 获取图片配对信息
        pairFirst.clear();
        pairSecond.clear();
        int pairSize = customCamera.pairFirst.size();
        for (int i = 0; i < pairSize; i ++) {
            pairFirst.add(customCamera.pairFirst.get(i));
            pairSecond.add(customCamera.pairSecond.get(i));
        }
    }

    void initUI() {
        my_result = findViewById(R.id.my_result);
        opencv_result = findViewById(R.id.opencv_result);
        photos = findViewById(R.id.photos);
//        stitch_log = findViewById(R.id.stitch_log);
        button_save = findViewById(R.id.save_button);
        button_camera = findViewById(R.id.camera_button);
        button_delete = findViewById(R.id.delete_button);
        button_my = findViewById(R.id.my_button);
        button_opencv = findViewById(R.id.opencv_button);
        my_progress = findViewById(R.id.my_progress);
        opencv_progress = findViewById(R.id.opencv_progress);

        photos.removeAllViews();// 移除所有子元素

        button_camera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openCustomCamera();
            }
        });

        button_delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteSelected();
            }
        });

        button_opencv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (threadCount[MODE_OPENCV] == 0) {
                    threadCount[MODE_OPENCV] ++;
                    stitch(MODE_OPENCV);
                } else {
                    infoLog("method of opencv is still running");
                }
            }
        });

        button_my.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (threadCount[MODE_MY] == 0) {
                    threadCount[MODE_MY] ++;
                    stitch(MODE_MY);
                } else {
                    infoLog("method of my is still running");
                }
            }
        });

        button_save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveResult();
            }
        });
    }

    void initApp() {
        mainHandler = new MainHandler();

        // 只适用 SDK > 23
        int hasCameraPermission = ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.CAMERA);
        if (hasCameraPermission == PackageManager.PERMISSION_GRANTED) {
            // 有调用相机权限
            addToLog("camera is ready");
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, PERMISSION_CAMERA_REQUEST_CODE);
        }
    }

    void openCustomCamera() {
        // 自定义相机
        addToLog("open custom camera");
//        customCamera1.show(getSupportFragmentManager(), "custom camera");
        customCamera.show(getSupportFragmentManager(), "custom camera");
    }

    void openSystemCamera() {
        // 系统相机
        addToLog("open system camera");
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) != null) {
            File photoFile = null;
            if (Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // Android 10 以上
                addToLog("android 10");
            } else {
                // Android 10 以下
                addToLog("not android 10");

                String photoName = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".jpg";
                photoFile = new File(appPath, photoName);
                photoPath = photoFile.getAbsolutePath();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    // Android 7 以上
                    addToLog("android 7");
                    photoUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photoFile);
                } else {
                    // Android 7 以下
                    addToLog("not android 7");
                }
            }

            if (photoUri != null) {
                intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                startActivityForResult(intent, PERMISSION_CAMERA_REQUEST_CODE);
            }
        }
    }

    void saveResult() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                saveBmp(my_bmp, 1);
            }
        }).start();
        new Thread(new Runnable() {
            @Override
            public void run() {
                saveBmp(opencv_bmp, 2);
            }
        }).start();
    }

    void addPhoto(String path) {
        Bitmap bitmap = BitmapFactory.decodeFile(path);

        final LinearLayout photo_border = new LinearLayout(this);
        ImageView photo_item = new ImageView(this);

        final LinearLayout.LayoutParams param_border = new LinearLayout.LayoutParams(300, 300);
        LinearLayout.LayoutParams param_item = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT);
        param_item.setMargins(20, 20, 20, 20);

        photo_border.setLayoutParams(param_border);
        photo_item.setLayoutParams(param_item);

        photo_border.addView(photo_item);
        photos.addView(photo_border);

        // 添加至列表
        photo_list.add(bitmap);
        photo_selected.add(0);
        photo_name.add(path);// 添加图片路径

        // 压缩图片并显示
        Matrix matrix = new Matrix();
        matrix.setScale(0.1f, 0.1f);
        Bitmap tmp = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        photo_item.setImageBitmap(tmp);

        // 添加选定功能
        photo_border.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int index = photos.indexOfChild(photo_border);
                if (photo_selected.get(index) == 0) {
                    photo_selected.set(index, 1);
                    photo_border.setBackgroundResource(R.color.greyC);
                } else {
                    photo_selected.set(index, 0);
                    photo_border.setBackgroundResource(R.color.white);
                }
            }
        });
    }

    void saveBmp(Bitmap bitmap, int mode) {// 1: My, 2: OpenCV
        String methodName = null;
        if (mode == MODE_MY) {
            methodName = "my";
        } else if (mode == MODE_OPENCV) {
            methodName = "opencv";
        }

        if (methodName != null && bitmap != null) {
            File file;
            for (int i = 0; i < 1000; i ++) {
                file = new File(appPath + "/" + methodName  + "_" + i + "_" + photo_list.size() + ".png");
                if (file.exists() == false) {
                    try {
                        file.createNewFile();
                        FileOutputStream stream = new FileOutputStream(file);
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
                        stream.flush();
                        stream.close();// TODO
                        infoLog("save " + methodName + " succeed");
                        return;
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    void stitch(final int mode) {
        if (photo_list.size() < 2) {
            addToLog("need at least 2 photos");
            return;// 图片数目不够
        }
        jniProgress(1, mode);// 重置进度条

        // 获取图片路径和旋转角度
        int photo_num = photo_list.size();
        final String[] imgPaths = new String[photo_num];
        final double[] imgRotations = new double[photo_num];
        for (int i = 0; i < photo_num; i ++) {
            imgPaths[i] = photo_name.get(i);
            imgRotations[i] = photo_rotation.get(i);
            if (mode == MODE_MY) {
                infoLog(imgPaths[i] + " " + imgRotations[i]);
            }
        }

        // 将配对信息从ArrayList转换为数组 TODO 配对信息处理
        final int[] indexFirst = new int[photo_num];
        final int[] indexSecond = new int[photo_num];
        for (int i = 0; i < photo_num; i ++) {
            indexFirst[i] = pairFirst.get(i);
            indexSecond[i] = pairSecond.get(i);
        }

        // 调用jni拼接
        new Thread(new Runnable() {
            @Override
            public void run() {
                Mat matBGR = new Mat();
                int result = main_test(
                        imgPaths,
                        imgRotations,
                        matBGR.getNativeObjAddr(),
                        indexFirst,
                        indexSecond,
                        mode
                );

                if (result != 0) {
                    jniProgress(-100, mode);
                    infoLog("failed");
                } else {
                    Bitmap bitmap = Bitmap.createBitmap(matBGR.cols(), matBGR.rows(), Bitmap.Config.ARGB_8888);

                    // BGR转RGB
                    Mat matRGBA = new Mat();
                    Imgproc.cvtColor(matBGR, matRGBA, Imgproc.COLOR_BGR2RGBA);
                    Utils.matToBitmap(matRGBA, bitmap);
                    saveBmp(bitmap, mode);

                    // 保存结果
                    if (mode == MODE_MY) {
                        my_bmp = bitmap;
                        infoLog("method of my finished");
                    } else if (mode == MODE_OPENCV) {
                        opencv_bmp = bitmap;
                        infoLog("method of opencv finished");
                    }

                    // 显示图片
                    jniProgress(100, mode);
                    final Bitmap finalBitmap = bitmap;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // 压缩图片并显示
                            Matrix matrix = new Matrix();
                            matrix.setScale(0.2f, 0.2f);
                            Bitmap bmp_thumbnail = Bitmap.createBitmap(finalBitmap, 0, 0, finalBitmap.getWidth(), finalBitmap.getHeight(), matrix, true);
                            if (mode == MODE_MY) {
                                my_result.setImageBitmap(bmp_thumbnail);
                            } else if (mode == MODE_OPENCV) {
                                opencv_result.setImageBitmap(bmp_thumbnail);
                            }
                            // 完成线程
                            threadCount[mode] --;
                        }
                    });
                }
            }
        }).start();
    }

    void deleteSelected() {
        for (int i = 0; i < photo_selected.size(); i ++) {
            int tmp = photo_selected.get(i);
            if (tmp == 1) {
                // 被选中
                deletePhoto(i);
                photos.removeViewAt(i);
                i --;
            }
        }
    }

    void deletePhoto(int index) {
        // 删除对应索引的图片, 不包括ImageView
        photo_list.remove(index);
        photo_selected.remove(index);
        photo_name.remove(index);
    }

    void addToLog(String log) {
//        String old_log = (String) stitch_log.getText();
//        stitch_log.setText(old_log + log + "\n");
    }

    class MainHandler extends Handler {
        public MainHandler(){}
        public MainHandler(Looper L) {
            super(L);
        }
        public void handleMessage(Message msg) {
            super.handleMessage(msg);

            Bundle bundle = msg.getData();

            // 打印log
            String log = bundle.getString("log");
            if (log != null) {
                addToLog(log);
            }

            // 修改进度
            final int progress = bundle.getInt("progress");
            final int mode = bundle.getInt("mode");
            if (progress > 0) {
                int new_width = (int)((double)progress * opencv_result.getWidth() / 100);
                LinearLayout.LayoutParams progressLayout = new LinearLayout.LayoutParams(new_width, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                if (mode == MODE_MY) {
                    my_progress.setLayoutParams(progressLayout);
                    my_progress.setBackgroundResource(R.color.greyC);
                } else if (mode == MODE_OPENCV) {
                    opencv_progress.setLayoutParams(progressLayout);
                    opencv_progress.setBackgroundResource(R.color.greyC);
                }
            } else if (progress < 0) {
                // 拼接失败
                if (mode == MODE_MY) {
                    my_progress.setBackgroundResource(R.color.red);
                } else if (mode == MODE_OPENCV) {
                    opencv_progress.setBackgroundResource(R.color.red);
                }
            }
        }
    }

    public static void jniLog(String log) {
        Message message = new Message();
        Bundle bundle = new Bundle();
        bundle.putString("log", log);
        message.setData(bundle);
        mainHandler.sendMessage(message);
    }

    public static void jniProgress(int progress, int mode) {
        Message message = new Message();
        Bundle bundle = new Bundle();
        bundle.putInt("progress", progress);
        bundle.putInt("mode", mode);// 1 for my, 2 for opencv
        message.setData(bundle);
        mainHandler.sendMessage(message);
    }

    /**
     * A native method that is implemented by the 'native-lib' native library,
     * which is packaged with this application.
     */
    public native int main_test(String[] imgPaths, double[] imgRotations, long matBGR, int[] pairFirst, int[] pairSecond, int mode);

    static public void infoLog(String log) {
        Log.i("fuck", log);
    }
}
