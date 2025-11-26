package com.example.bluetoothmouse

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnBluetooth = findViewById<Button>(R.id.btn_bluetooth_settings)
        val btnMouseTest = findViewById<Button>(R.id.btn_mouse_test)
        val btnStreaming = findViewById<Button>(R.id.btn_streaming)

        btnBluetooth.setOnClickListener {
            startActivity(Intent(this, BluetoothActivity::class.java))
        }

        btnMouseTest.setOnClickListener {
            startActivity(Intent(this, MouseTestActivity::class.java))
        }

        btnStreaming.setOnClickListener {
             startActivity(Intent(this, StreamingActivity::class.java))
        }
    }
}