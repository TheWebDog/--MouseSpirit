package com.example.bluetoothmouse

object HidConstants {
    // 标准鼠标 HID Report Descriptor (相对模式)
    // 这是一个最通用的鼠标描述符，兼容性最好
    val MOUSE_REPORT_DESC = byteArrayOf(
        0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop Ctrls)
        0x09.toByte(), 0x02.toByte(), // Usage (Mouse)
        0xA1.toByte(), 0x01.toByte(), // Collection (Application)
        0x09.toByte(), 0x01.toByte(), //   Usage (Pointer)
        0xA1.toByte(), 0x00.toByte(), //   Collection (Physical)
        0x05.toByte(), 0x09.toByte(), //     Usage Page (Button)
        0x19.toByte(), 0x01.toByte(), //     Usage Minimum (0x01)
        0x29.toByte(), 0x03.toByte(), //     Usage Maximum (0x03)
        0x15.toByte(), 0x00.toByte(), //     Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(), //     Logical Maximum (1)
        0x95.toByte(), 0x03.toByte(), //     Report Count (3)
        0x75.toByte(), 0x01.toByte(), //     Report Size (1)
        0x81.toByte(), 0x02.toByte(), //     Input (Data,Var,Abs,No Wrap,Linear,Preferred State,No Null Position)
        0x95.toByte(), 0x01.toByte(), //     Report Count (1)
        0x75.toByte(), 0x05.toByte(), //     Report Size (5)
        0x81.toByte(), 0x03.toByte(), //     Input (Const,Var,Abs,No Wrap,Linear,Preferred State,No Null Position)
        0x05.toByte(), 0x01.toByte(), //     Usage Page (Generic Desktop Ctrls)
        0x09.toByte(), 0x30.toByte(), //     Usage (X)
        0x09.toByte(), 0x31.toByte(), //     Usage (Y)
        0x15.toByte(), 0x81.toByte(), //     Logical Minimum (-127)
        0x25.toByte(), 0x7F.toByte(), //     Logical Maximum (127)
        0x75.toByte(), 0x08.toByte(), //     Report Size (8)
        0x95.toByte(), 0x02.toByte(), //     Report Count (2)
        0x81.toByte(), 0x06.toByte(), //     Input (Data,Var,Rel,No Wrap,Linear,Preferred State,No Null Position)
        0x09.toByte(), 0x38.toByte(), //     Usage (Wheel)
        0x15.toByte(), 0x81.toByte(), //     Logical Minimum (-127)
        0x25.toByte(), 0x7F.toByte(), //     Logical Maximum (127)
        0x75.toByte(), 0x08.toByte(), //     Report Size (8)
        0x95.toByte(), 0x01.toByte(), //     Report Count (1)
        0x81.toByte(), 0x06.toByte(), //     Input (Data,Var,Rel,No Wrap,Linear,Preferred State,No Null Position)
        0xC0.toByte(),                //   End Collection
        0xC0.toByte()                 // End Collection
    )
}