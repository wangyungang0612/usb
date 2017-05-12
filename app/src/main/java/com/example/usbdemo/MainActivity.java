package com.example.usbdemo;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Iterator;

@SuppressLint("HandlerLeak")
public class MainActivity extends Activity {
	private final String TAG = MainActivity.class.getSimpleName();
	// view
	private TextView mTextView;
	private Button mButton, mClearButton;
	private CheckBox mCheckBox;

	// usb class
	private UsbManager mUsbManager;
	// 设备VID和PID
	private int VendorID;
	private int ProductID;
	private UsbDevice myUsbDevice;
	private UsbInterface Interface1, Interface2;
	private UsbEndpoint epBulkOut, epBulkIn, epControl, epIntEndpointOut,
			epIntEndpointIn;
	private UsbDeviceConnection myDeviceConnection;

	private boolean isExits = false;
	private boolean isGet = false;
	
	private final int BUF_SIZE = 256;// 一次性读取多少字节！此处应该设置比仪器端发送过来的数据的字节数至少大2！
									// 如果设置较小，则接收数据端点会阻塞！从而无法读取到数据！
									// 比如：如果仪器端一次发送的字节数为20，则BUF_SIZE至少设置为22！

	private final int MSG_USB_INSERT = 0xAA;
	private final int MSG_USB_UNINSERT = 0xBB;
	private final int MSG_USB_GETDATA = 0xCC;
	
	Handler handler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			super.handleMessage(msg);
			switch (msg.what) {
			case MSG_USB_GETDATA:
				if (isGet)
					mTextView.setText(mTextView.getText().toString() + "\n"
							+ bytes2HexString((byte[]) msg.obj));


				break;
			case MSG_USB_INSERT:
				initUSB();
				mCheckBox.setChecked(true);
				break;
			case MSG_USB_UNINSERT:
				releaseUSB();
				mCheckBox.setChecked(false);
				break;
			}
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		initView();
		registerReceiver(mUsbDeviceReceiver, new IntentFilter(
				UsbManager.ACTION_USB_DEVICE_ATTACHED));
		registerReceiver(mUsbDeviceReceiver, new IntentFilter(
				UsbManager.ACTION_USB_DEVICE_DETACHED));
		Intent i = getIntent();
		String action = i.getAction();
		if (action.equals("android.hardware.usb.action.USB_DEVICE_ATTACHED")) {
			initUSB();
			mCheckBox.setChecked(true);
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		isExits = true;
		releaseUSB();
		unregisterReceiver(mUsbDeviceReceiver);
	}

	private void initUSB() {
		mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE); // 获取UsbManager
		enumerateDevice(mUsbManager);
		getDeviceInterface();
		assignEndpoint(Interface2);
		openDevice(Interface2);
		new Thread() {
			@Override
			public void run() {
				super.run();
				while (!isExits) {
//					try {
//						Thread.sleep(500);
//					} catch (InterruptedException e) {
//						// TODO Auto-generated catch block
//						e.printStackTrace();
//					}
					byte[] data = receiveMessageFromPoint();
					if (data == null) {
						continue;
					}
					Message msg = new Message();
					msg.what = MSG_USB_GETDATA;
					msg.obj = data;
					L.e("qqqqqqqqq"+data);
					handler.sendMessage(msg);
				}
			}
		}.start();
	}

	private void releaseUSB() {
		myUsbDevice = null;
		Interface1 = null;
		Interface2 = null;
		epBulkOut = null;
		epBulkIn = null;
		epControl = null;
		epIntEndpointOut = null;
		epIntEndpointIn = null;
		myDeviceConnection = null;
	}

	// [start] bytes2HexString byte与16进制字符串的互相转换
	public String bytes2HexString(byte[] b) {
		String ret = "";
		for (int i = 0; i < b.length; i++) {
			String hex = Integer.toHexString(b[i] & 0xFF);
			if (hex.length() == 1) {
				hex = '0' + hex;
			}
			ret += hex.toUpperCase();
		}
		return ret;
	}

	private void initView() {
		mTextView = (TextView) findViewById(R.id.txtReciver);
		mButton = (Button) findViewById(R.id.btnSend);
		mClearButton = (Button) findViewById(R.id.btnClear);
		mCheckBox = (CheckBox) findViewById(R.id.cbUSBStatue);
		mButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				byte[] message = new byte[4];
				message[0] = 0x1A;
				message[1] = 0x2A;
				message[2] = 0x3A;
				message[3] = 0x4B;
				sendMessageToPoint(message);
			}
		});
		mClearButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				mTextView.setText("");
			}
		});
	}

	// 枚举设备函数
	private void enumerateDevice(UsbManager mUsbManager) {
		System.out.println("dddd开始进行枚举设备!");
		if (mUsbManager == null) {
			System.out.println("dddd创建UsbManager失败，请重新启动应用！");
			mTextView.setText("创建UsbManager失败，请重新启动应用！");
			return;
		}
		HashMap<String, UsbDevice> deviceList = mUsbManager.getDeviceList();
		if (!(deviceList.isEmpty())) {
			// deviceList不为空
			System.out.println("dddddeviceList is not null!");
			Iterator<UsbDevice> deviceIterator = deviceList.values().iterator();
			while (deviceIterator.hasNext()) {
				UsbDevice device = deviceIterator.next();
				Log.i(TAG, "ddddDeviceInfo: " + device.getVendorId() + " , "
						+ device.getProductId());
				// 保存设备VID和PID
				VendorID = device.getVendorId();
				ProductID = device.getProductId();
				// 保存匹配到的设备
				if (VendorID == 6790 && ProductID == 29987) {
					myUsbDevice = device; // 获取USBDevice
					System.out.println("dddd发现待匹配设备:" + device.getDeviceName()
							+ device.getVendorId() + ","
							+ device.getProductId());
					Context context = getApplicationContext();
					Toast.makeText(context, "发现待匹配设备" + device.getDeviceName(),
							Toast.LENGTH_SHORT).show();
				}
			}
		} else {
			System.out.println("dddddeviceList is null!");
			mTextView.setText("请连接USB设备至手机！");
			Context context = getApplicationContext();
			Toast.makeText(context, "请连接USB设备至手机！", Toast.LENGTH_SHORT).show();
		}
	}

	// 寻找设备接口
	private void getDeviceInterface() {
		if (myUsbDevice != null) {
			Log.i(TAG, "interfaceCounts : " + myUsbDevice.getInterfaceCount());
			for (int i = 0; i < myUsbDevice.getInterfaceCount(); i++) {
				UsbInterface intf = myUsbDevice.getInterface(i);
				if (i == 0) {
					Interface1 = intf; // 保存设备接口
					Interface2 = intf;
					System.out.println("dddd成功获得设备接口1:" + Interface1.getId());
				}
				if (i == 1) {
					Interface2 = intf;
					System.out.println("dddd成功获得设备接口2:" + Interface2.getId());
				}
			}
		} else {
			System.out.println("设备为空！");
		}
	}

	// 分配端点，IN | OUT，即输入输出；可以通过判断
	private UsbEndpoint assignEndpoint(UsbInterface mInterface) {
		for (int i = 0; i < mInterface.getEndpointCount(); i++) {
			UsbEndpoint ep = mInterface.getEndpoint(i);
			// look for bulk endpoint
			if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
				if (ep.getDirection() == UsbConstants.USB_DIR_OUT) {
					epBulkOut = ep;
					System.out.println("Find the BulkEndpointOut," + "index:"
							+ i + "," + "使用端点号："
							+ epBulkOut.getEndpointNumber());
				} else {
					epBulkIn = ep;
					System.out
							.println("Find the BulkEndpointIn:" + "index:" + i
									+ "," + "使用端点号："
									+ epBulkIn.getEndpointNumber());
				}
			}
			// look for contorl endpoint
			if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_CONTROL) {
				epControl = ep;
				System.out.println("find the ControlEndPoint:" + "index:" + i
						+ "," + epControl.getEndpointNumber());
			}
			// look for interrupte endpoint
			if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT) {
				if (ep.getDirection() == UsbConstants.USB_DIR_OUT) {
					epIntEndpointOut = ep;
					System.out.println("find the InterruptEndpointOut:"
							+ "index:" + i + ","
							+ epIntEndpointOut.getEndpointNumber());
				}
				if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
					epIntEndpointIn = ep;
					System.out.println("find the InterruptEndpointIn:"
							+ "index:" + i + ","
							+ epIntEndpointIn.getEndpointNumber());
				}
			}
		}
		if (epBulkOut == null && epBulkIn == null && epControl == null
				&& epIntEndpointOut == null && epIntEndpointIn == null) {
			throw new IllegalArgumentException("not endpoint is founded!");
		}
		return epIntEndpointIn;
	}

	// 打开设备
	private void openDevice(UsbInterface mInterface) {
		if (mInterface != null) {
			UsbDeviceConnection conn = null;
			// 在open前判断是否有连接权限；对于连接权限可以静态分配，也可以动态分配权限
			if (mUsbManager.hasPermission(myUsbDevice)) {
				conn = mUsbManager.openDevice(myUsbDevice);
			}

			if (conn == null) {
				System.out.println("没有连接权限！");
				return;
			}

			if (conn.claimInterface(mInterface, true)) {
				myDeviceConnection = conn;
				if (myDeviceConnection != null)// 到此你的android设备已经连上zigbee设备
					System.out.println("open设备成功！");
				final String mySerial = myDeviceConnection.getSerial();
				System.out.println("设备serial number：" + mySerial);
			} else {
				System.out.println("无法打开连接通道。");
				conn.close();
			}
		}
	}

	// 发送数据
	private void sendMessageToPoint(byte[] buffer) {
		if (myDeviceConnection == null) {
			return;
		}
		// bulkOut传输
		if (myDeviceConnection
				.bulkTransfer(epBulkOut, buffer, buffer.length, 0) < 0)
			System.out.println("bulkOut返回输出为  负数");
		else {
			System.out.println("Send Message Succese！");
		}
	}

	// 从设备接收数据bulkIn
	private byte[] receiveMessageFromPoint() {
		byte[] buffer = new byte[BUF_SIZE];
		if (myDeviceConnection == null) {
			return null;
		}
		if (myDeviceConnection.bulkTransfer(epBulkIn, buffer, buffer.length,
				2000) < 0) {
			System.out.println("bulkIn返回输出为  负数");
			isGet = false;
		} else {
			isGet = true;
			System.out.println("Receive Message Succese！"
					+ bytes2HexString(buffer));
			// + "数据返回"
			// + myDeviceConnection.bulkTransfer(epBulkIn, buffer,
			// buffer.length, 3000)
		}
		return buffer;
	}

	private final BroadcastReceiver mUsbDeviceReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
				UsbDevice deviceFound = (UsbDevice) intent
						.getParcelableExtra(UsbManager.EXTRA_DEVICE);
				Toast.makeText(
						MainActivity.this,
						"ACTION_USB_DEVICE_ATTACHED: \n"
								+ deviceFound.toString(), Toast.LENGTH_LONG)
						.show();
				handler.sendEmptyMessage(MSG_USB_INSERT);
			} else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
				UsbDevice device = (UsbDevice) intent
						.getParcelableExtra(UsbManager.EXTRA_DEVICE);
				Toast.makeText(MainActivity.this,
						"ACTION_USB_DEVICE_DETACHED: \n" + device.toString(),
						Toast.LENGTH_LONG).show();
				handler.sendEmptyMessage(MSG_USB_UNINSERT);
			}
		}

	};

}
