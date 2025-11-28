package com.limelight;

import android.util.Log;

import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.Logger;

public class LimeLog {
    private static final String TAG = "Moonlight";

    public static void info(String msg) {
        Log.i(TAG, msg);
    }
    
    public static void warning(String msg) {
        Log.w(TAG, msg);
    }
    
    public static void severe(String msg) {
        Log.e(TAG, msg);
    }
}
