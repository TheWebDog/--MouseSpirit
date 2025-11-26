package com.example.bluetoothmouse

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import android.view.MenuItem
import kotlin.math.roundToInt

class MouseTestActivity : AppCompatActivity(), HidDeviceHelper.HidConnectionListener {

    private lateinit var touchpadArea: View
    private lateinit var scrollStrip: View
    private lateinit var btnLeft: Button
    private lateinit var btnMiddle: Button
    private lateinit var btnRight: Button
    private lateinit var switchMode: Switch
    
    private var hidHelper: HidDeviceHelper? = null
    // 移除绝对模式状态，保持标准鼠标行为
    private var isAbsoluteMode = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mouse_test)

        hidHelper = HidDeviceHelper(this, this)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""

        touchpadArea = findViewById(R.id.touchpad_area)
        scrollStrip = findViewById(R.id.scroll_strip)
        btnLeft = findViewById(R.id.btn_left_click)
        btnMiddle = findViewById(R.id.btn_middle_click)
        btnRight = findViewById(R.id.btn_right_click)
        switchMode = findViewById(R.id.switch_mouse_mode)

        // 暂时禁用模式切换，保持开启状态无效果
        switchMode.visibility = View.GONE

        touchpadArea.setOnTouchListener { _, event ->
            handleTouchpadEvent(event)
            true
        }

        scrollStrip.setOnTouchListener { _, event ->
            handleScrollEvent(event)
            true
        }

        btnLeft.setOnTouchListener { _, event ->
            handleButtonClick(event, 1) // Left Button Bit 0
            true
        }

        btnRight.setOnTouchListener { _, event ->
            handleButtonClick(event, 2) // Right Button Bit 1
            true
        }

        btnMiddle.setOnTouchListener { _, event ->
            handleButtonClick(event, 4) // Middle Button Bit 2
            true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hidHelper?.cleanup()
    }

    private var lastX = 0f
    private var lastY = 0f

    private fun handleTouchpadEvent(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.x - lastX).roundToInt()
                val dy = (event.y - lastY).roundToInt()
                
                if (dx != 0 || dy != 0) {
                     // 限制范围 -127 to 127
                     val clampedDx = dx.coerceIn(-127, 127)
                     val clampedDy = dy.coerceIn(-127, 127)
                     
                     hidHelper?.sendReport(currentButtons, clampedDx, clampedDy, 0)
                }
                
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                 hidHelper?.sendReport(currentButtons, 0, 0, 0)
            }
        }
    }
    
    private var lastScrollY = 0f

    private fun handleScrollEvent(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastScrollY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = (event.y - lastScrollY).roundToInt()
                val scrollAmount = -dy / 10 
                
                if (scrollAmount != 0) {
                    val clampedScroll = scrollAmount.coerceIn(-127, 127)
                    hidHelper?.sendReport(currentButtons, 0, 0, clampedScroll)
                }
                lastScrollY = event.y
            }
        }
    }

    private var currentButtons = 0

    private fun handleButtonClick(event: MotionEvent, buttonBit: Int) {
         when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                currentButtons = currentButtons or buttonBit
                hidHelper?.sendReport(currentButtons, 0, 0, 0)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                currentButtons = currentButtons and buttonBit.inv()
                hidHelper?.sendReport(currentButtons, 0, 0, 0)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDeviceConnected(device: BluetoothDevice) {
        runOnUiThread {
            Toast.makeText(this, "已连接: ${device.name}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDeviceDisconnected(device: BluetoothDevice) {
        runOnUiThread {
            Toast.makeText(this, "已断开: ${device.name}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onHidStateChanged(state: String) {
    }
}