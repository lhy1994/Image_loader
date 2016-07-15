package com.liuhaoyuan.imageloader;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.FileDescriptor;

import static android.graphics.BitmapFactory.*;

/**
 * Created by liuhaoyuan on 2016/7/15.
 */
public class ImageResizer {

    public Bitmap decodeSampledBitmapFromResource(Resources res,int resId,int reqWidth,int reqHeight){
         Options options=new Options();
        //先只读出宽高属性
        options.inJustDecodeBounds=true;
        BitmapFactory.decodeResource(res,resId,options);
        //计算储采样率
        options.inSampleSize=calculateInSampleSize(options,reqWidth,reqHeight);

        options.inJustDecodeBounds=false;
        return BitmapFactory.decodeResource(res,resId,options);
    }

    public Bitmap decodeSampledBitmapFromFileDescriptor(FileDescriptor descriptor,int reqWidth,int reqHeight){
        BitmapFactory.Options options=new Options();
        //先只读出宽高属性
        options.inJustDecodeBounds=true;
        BitmapFactory.decodeFileDescriptor(descriptor,null,options);

        options.inSampleSize=calculateInSampleSize(options,reqWidth,reqHeight);
        options.inJustDecodeBounds=false;
        return BitmapFactory.decodeFileDescriptor(descriptor,null,options);
    }

    //计算采样率
    private int calculateInSampleSize(Options options, int reqWidth, int reqHeight) {
        if (reqHeight==0||reqWidth==0){
            return 1;
        }
        int height=options.outHeight;
        int width=options.outWidth;
        int inSampleSize=1;

        //如果实际宽高大于要求宽高，则压缩
        if (height>reqHeight||width>reqWidth){
            int halfHeight=height/2;
            int halfwidth=width/2;

            while((halfHeight/inSampleSize)>=reqHeight && (halfwidth/inSampleSize)>=reqWidth ){
                inSampleSize*=2;
            }
        }
        return inSampleSize;
    }
}
