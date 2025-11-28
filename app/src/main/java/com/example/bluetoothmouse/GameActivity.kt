package com.example.bluetoothmouse

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.limelight.nvstream.NvConnectionListener
import com.limelight.nvstream.av.audio.AudioRenderer
import com.limelight.nvstream.av.video.VideoDecoderRenderer
import com.limelight.nvstream.jni.MoonBridge
import java.util.concurrent.Executors

class GameActivity : AppCompatActivity(), SurfaceHolder.Callback, NvConnectionListener {

    private lateinit var surfaceView: SurfaceView
    private lateinit var overlayStatus: TextView
    private val executor = Executors.newSingleThreadExecutor()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Fullscreen immersive mode
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        setContentView(R.layout.activity_game)
        
        surfaceView = findViewById(R.id.surface_video)
        overlayStatus = findViewById(R.id.tv_overlay_status)
        
        surfaceView.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        startStream(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopStream()
    }

    private fun startStream(holder: SurfaceHolder) {
        val context = GlobalContext.connectionContext
        if (context == null) {
            Toast.makeText(this, "No connection context found!", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        executor.execute {
            // 1. Initialize Renderers
            // Currently placeholders, will implement MediaCodec later
            val videoRenderer = object : VideoDecoderRenderer() {
                override fun setup(format: Int, width: Int, height: Int, redrawRate: Int): Int = 0
                override fun start() {}
                override fun stop() {}
                override fun submitDecodeUnit(data: ByteArray, length: Int, type: Int, frameNumber: Int, frameType: Int, latency: Char, receiveTime: Long, enqueueTime: Long): Int = 0
                override fun cleanup() {}
                override fun getCapabilities(): Int = MoonBridge.CAPABILITY_DIRECT_SUBMIT
                override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {}
            }

            val audioRenderer = object : AudioRenderer {
                override fun setup(config: MoonBridge.AudioConfiguration, sampleRate: Int, samplesPerFrame: Int): Int = 0
                override fun start() {}
                override fun stop() {}
                override fun playDecodedAudio(audioData: ShortArray) {}
                override fun cleanup() {}
            }

            MoonBridge.setupBridge(videoRenderer, audioRenderer, this)

            // 2. Prepare Connection Parameters from Context
            val config = context.streamConfig
            val riKeyBytes = context.riKey?.encoded ?: ByteArray(16)
            val riKeyIv = ByteArray(16) // IV is typically zeroed or derived, Moonlight uses zero-IV for initial RI handshake often or specific logic

            // 3. Start Connection (Blocking)
            runOnUiThread { overlayStatus.text = "Connecting via RTSP..." }
            
            val status = MoonBridge.startConnection(
                context.serverAddress.address,
                context.serverAppVersion,
                context.serverGfeVersion,
                context.rtspSessionUrl,
                context.serverCodecModeSupport,
                config.width, config.height, config.refreshRate,
                config.bitrate, config.maxPacketSize,
                config.remote,
                config.audioConfiguration.toInt(),
                config.supportedVideoFormats,
                config.clientRefreshRateX100,
                riKeyBytes, riKeyIv,
                0, // videoCapabilities
                config.colorSpace,
                config.colorRange
            )
            
            runOnUiThread {
                if (status == 0) { // ML_ERROR_GRACEFUL_TERMINATION
                     Toast.makeText(this, "Stream ended normally", Toast.LENGTH_SHORT).show()
                } else {
                     Toast.makeText(this, "Stream ended with error: $status", Toast.LENGTH_LONG).show()
                }
                finish()
            }
        }
    }
    
    private fun stopStream() {
        MoonBridge.stopConnection()
        MoonBridge.cleanupBridge()
    }

    // NvConnectionListener implementation
    override fun stageStarting(stage: String) {
        runOnUiThread { overlayStatus.text = "Stage: $stage" }
    }
    
    override fun stageComplete(stage: String) {
        runOnUiThread { overlayStatus.text = "Stage Complete: $stage" }
    }
    
    override fun stageFailed(stage: String, portFlags: Int, errorCode: Int) {
         runOnUiThread { 
             overlayStatus.text = "Failed: $stage ($errorCode)"
             Toast.makeText(this, "Connection Failed at $stage: $errorCode", Toast.LENGTH_LONG).show()
         }
    }
    
    override fun connectionStarted() {
        runOnUiThread { 
            overlayStatus.text = "" // Hide text on success
            overlayStatus.visibility = android.view.View.GONE
            Toast.makeText(this, "Connection Started!", Toast.LENGTH_SHORT).show() 
        }
    }
    
    override fun connectionTerminated(errorCode: Int) {
        runOnUiThread {
            if (errorCode != 0) {
                Toast.makeText(this, "Terminated: $errorCode", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    override fun connectionStatusUpdate(connectionStatus: Int) {
        if (connectionStatus == MoonBridge.CONN_STATUS_POOR) {
             runOnUiThread { Toast.makeText(this, "Bad Connection!", Toast.LENGTH_SHORT).show() }
        }
    }
    
    override fun displayMessage(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }
    
    override fun displayTransientMessage(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }
    
    override fun rumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) {}
    override fun rumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) {}
    override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {}
    override fun setMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHz: Short) {}
    override fun setControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) {}
}
