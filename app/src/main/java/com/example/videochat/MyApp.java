package com.example.videochat;


import android.app.Application;
import com.google.firebase.FirebaseApp;

import org.webrtc.PeerConnectionFactory;

public class MyApp extends Application {
    private static boolean pcfInitialized = false;

    @Override
    public void onCreate() {
        super.onCreate();
        FirebaseApp.initializeApp(this);
        if (!pcfInitialized) {
            PeerConnectionFactory.InitializationOptions options =
                    PeerConnectionFactory.InitializationOptions.builder(getApplicationContext())
                            .setEnableInternalTracer(true) // optional
                            .createInitializationOptions();
            PeerConnectionFactory.initialize(options);
            pcfInitialized = true;
        }
    }
}