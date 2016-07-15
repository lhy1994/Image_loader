package com.liuhaoyuan.imageloader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.StatFs;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import com.jakewharton.disklrucache.DiskLruCache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by liuhaoyuan on 2016/7/15.
 */
public class ImageLoader {

    private LruCache<String, Bitmap> mMemoryCache;
    private DiskLruCache mDiskLruCache;
    private boolean isDiskLruCacheCreated = false;
    private final long DISK_CACHE_SIZE = 1024 * 1024 * 50;
    private final int IO_BUFFER_SIZE = 8 * 1024;
    private static final int TAG_KEY_URI = 123;

    //构建线程池
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE = CPU_COUNT + 1;
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
    private static final long KEEP_ALIVE = 10l;
    private static final ThreadFactory THREAD_FACTORY = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            return new Thread(runnable, "ImageLoader#" + mCount.getAndIncrement());
        }
    };

    public static final Executor THREAD_POOL_EXECUTER = new ThreadPoolExecutor(CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE, TimeUnit.SECONDS, new LinkedBlockingDeque<Runnable>(), THREAD_FACTORY);

   private android.os.Handler mMainHandler=new Handler(Looper.getMainLooper()){
       @Override
       public void handleMessage(Message msg) {
           LoaderResult loaderResult= (LoaderResult) msg.obj;
           ImageView imageView=loaderResult.imageView;
           String url= (String) imageView.getTag(TAG_KEY_URI);
           //检查Imageview的url是否发生变化
           if (url.equals(loaderResult.url)){
               imageView.setImageBitmap(loaderResult.bitmap);
           }
       }
   };
    private ImageResizer imageResizer = new ImageResizer();

    private ImageLoader(Context context) {
        Context mContext = context.getApplicationContext();

        //初始化内存缓存，设置缓存大小为最大内存的1/8，重写LRUcache的sizeof方法，
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;
        mMemoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getRowBytes() * bitmap.getHeight() * 1024;
            }
        };

        //初始化磁盘缓存，设置并判断当前可用空间是否足够
        File diskCacheDir = getDiskCachedir(mContext, "bitmap");
        if (!diskCacheDir.exists()) {
            diskCacheDir.mkdir();
        }

        if (getUsableSpace(diskCacheDir) > DISK_CACHE_SIZE) {
            try {
                mDiskLruCache = DiskLruCache.open(diskCacheDir, 1, 1, DISK_CACHE_SIZE);
                isDiskLruCacheCreated = true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    //同步加载图片
    public Bitmap loadBitmap(String url, int reqWidth, int reqHeight) {
        Bitmap bitmap = loadBitmapFromMemoryCache(url);
        if (bitmap != null) {
            return bitmap;
        }

        try {
            bitmap = loadBitmapFromDiskLruCache(url, reqWidth, reqHeight);
            if (bitmap != null) {
                return bitmap;
            }

            bitmap = loadBitmapFromHttp(url, reqWidth, reqHeight);
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (bitmap == null && isDiskLruCacheCreated) {
            bitmap = downloadBitmapFromUrl(url);
        }

        return bitmap;
    }

    //异步加载
    public void bindBitmap(final String url, final ImageView imageView, final int reqWidth, final int reqHeight) {
        imageView.setTag(TAG_KEY_URI, url);
        final Bitmap bitmap = loadBitmapFromMemoryCache(url);
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap);
            return;
        }

        Runnable loadBitmapTask = new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap1 = loadBitmap(url, reqWidth, reqHeight);
                if (bitmap1 != null) {
                    LoaderResult result=new LoaderResult(imageView,url,bitmap1);
                    mMainHandler.obtainMessage(1,result).sendToTarget();
                }
            }
        };
        THREAD_POOL_EXECUTER.execute(loadBitmapTask);
    }

    //向内存缓存中添加图片
    private void addBitmapToMemoryCache(String key, Bitmap bitmap) {
        if (getBitmapFromMemoryCache(key) == null) {
            mMemoryCache.put(key, bitmap);
        }
    }

    //根据key值从内存缓存中取图片
    private Bitmap getBitmapFromMemoryCache(String key) {
        return mMemoryCache.get(key);
    }

    //根据URL从内存缓存中读取图片
    private Bitmap loadBitmapFromMemoryCache(String url) {
        String key = hashKeyFromUrl(url);
        Bitmap bitmap = getBitmapFromMemoryCache(key);
        return bitmap;
    }

    //从磁盘缓存读图片
    private Bitmap loadBitmapFromDiskLruCache(String url, int reqWidth, int reqHeight) throws IOException {
        //判断是否是在主线程中
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.e("MyError", "load bitmap fromUI THread!");
        }

        if (mDiskLruCache == null) {
            return null;
        }

        Bitmap bitmap = null;
        //根据图片URL计算出key值
        String key = hashKeyFromUrl(url);
        DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);
        if (snapshot != null) {
            //通过diskLruCache获得文件输入流
            FileInputStream fileInputStream = (FileInputStream) snapshot.getInputStream(0);
            FileDescriptor fileDescriptor = fileInputStream.getFD();
            //通过文件描述符加载并压缩图片
            bitmap = imageResizer.decodeSampledBitmapFromFileDescriptor(fileDescriptor, reqWidth, reqHeight);
            if (bitmap != null) {
                //如果读到图片，就把他加入到内存缓存
                addBitmapToMemoryCache(key, bitmap);
            }
        }
        return bitmap;
    }

    //从网络获取图片,并存入磁盘缓存,若没有建立磁盘缓存，则返回null
    private Bitmap loadBitmapFromHttp(String url, int reqWidth, int reqHeight) throws IOException {
        //判断是否是在主线程中
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.e("MyError", "load bitmap fromUI THread!");
        }
        if (mDiskLruCache == null) {
            return null;
        }

        String key = hashKeyFromUrl(url);
        //通过editor获得输出流，从而写入磁盘缓存
        DiskLruCache.Editor editor = mDiskLruCache.edit(key);
        if (editor != null) {
            OutputStream outputStream = editor.newOutputStream(0);
            if (downlaodUrlToStream(url, outputStream)) {
                editor.commit();
            } else {
                editor.abort();
            }
            mDiskLruCache.flush();
        }
        return loadBitmapFromDiskLruCache(url, reqWidth, reqHeight);
    }

    //通过网络获取数据并写入outputstream
    public boolean downlaodUrlToStream(String urlString, OutputStream outputStream) {
        HttpURLConnection urlConnection = null;
        BufferedOutputStream out = null;
        BufferedInputStream in = null;

        try {
            URL url = new URL(urlString);
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(), IO_BUFFER_SIZE);
            out = new BufferedOutputStream(urlConnection.getOutputStream(), IO_BUFFER_SIZE);

            int b = 0;
            while ((b = in.read()) != -1) {
                out.write(b);
            }
            return true;
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            try {
                if (out != null) {
                    out.close();
                }
                if (in != null) {
                    in.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return false;
    }

    //从网络下载图片
    private Bitmap downloadBitmapFromUrl(String urlString) {
        Bitmap bitmap = null;
        HttpURLConnection urlConnection = null;
        BufferedInputStream in = null;

        try {
            URL url = new URL(urlString);
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(), IO_BUFFER_SIZE);
            bitmap = BitmapFactory.decodeStream(in);
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return bitmap;
    }

    //将图片URL通过MD5算法转换为缓存文件名字
    private String hashKeyFromUrl(String url) {
        String cacheKey;
        try {
            final MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.update(url.getBytes());
            cacheKey = bytesToHexString(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            cacheKey = String.valueOf(url.hashCode());
        }
        return cacheKey;
    }

    //将字节转换为十六进制
    private String bytesToHexString(byte[] bytes) {
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            String hex = Integer.toHexString(0XFF & bytes[i]);
            if (hex.length() == 1) {
                stringBuilder.append('0');
            }
            stringBuilder.append(hex);
        }
        return stringBuilder.toString();
    }

    //获取磁盘缓存路径
    public File getDiskCachedir(Context context, String uniqueName) {
        boolean externalStorageAvailable = Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
        final String cachePath;

        //如果当前有Sd卡，就存储在SD卡的缓存目录，否则存储在内部存储的缓存目录
        if (externalStorageAvailable) {
            cachePath = context.getExternalCacheDir().getPath();
        } else {
            cachePath = context.getCacheDir().getPath();
        }
        return new File(cachePath + File.separator + uniqueName);
    }

    //返回可用空间
    private long getUsableSpace(File path) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            return path.getUsableSpace();
        }
        final StatFs stats = new StatFs(path.getPath());
        return (long) stats.getBlockSize() * (long) stats.getAvailableBlocks();
    }
    private static class LoaderResult{
        public ImageView imageView;
        public String url;
        public Bitmap bitmap;

        public LoaderResult(ImageView imageView, String url, Bitmap bitmap) {
            this.imageView = imageView;
            this.url = url;
            this.bitmap = bitmap;
        }
    }
}
