/*
 * Appcelerator Titanium Mobile - Bluetooth Low Energy (BLE) Module
 * Copyright (c) 2020 by Axway, Inc. All Rights Reserved.
 * Proprietary and Confidential - This source code is not for redistribution
 */
package appcelerator.ble;

import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_INDICATE;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import java.util.UUID;
import org.appcelerator.kroll.KrollDict;
import ti.modules.titanium.BufferProxy;

@SuppressLint("LongLogTag")
public class TiBleCentralOperationManager
{

	private static final String LCAT = "TiBleCentralOperationManager";
	private static final String UUID_CLIENT_CHARACTERISTIC_CONFIGURATION = "00002902-0000-1000-8000-00805f9b34fb";
	private final Context context;
	private final TiBLECentralManagerProxy centralManagerProxy;
	private final TiBLEPeripheralProxy peripheralProxy;
	private final boolean autoConnect;
	private BluetoothGatt bluetoothGatt;
	private ConnectionState connectionState = ConnectionState.New;

	// Android's BluetoothGatt only permits one outstanding GATT operation (read/write
	// characteristic, read/write descriptor, discoverServices, readRemoteRssi) at a time on a
	// given connection - issuing a second one before the first's own BluetoothGattCallback method
	// has fired silently drops or corrupts whichever one wasn't actually in progress. Confirmed as
	// the root cause of a generic-ble notify subscription (subscribeToCharacteristic's
	// writeDescriptor call) being silently dropped by a characteristic write issued ~500ms later,
	// before the subscribe had actually completed - the write (issued last) succeeded, the earlier
	// subscribe never did, and no data ever arrived. Every method below that touches bluetoothGatt
	// is queued through enqueueGattOperation/completeGattOperation instead of calling it directly.
	// Guarded by gattQueueLock, not just a plain field - enqueueGattOperation runs on the Kroll/JS
	// thread, completeGattOperation runs from BluetoothGattCallback methods on Android's own
	// Binder thread pool, genuinely concurrent with each other.
	private final Object gattQueueLock = new Object();
	private final java.util.Queue<Runnable> gattOperationQueue = new java.util.LinkedList<>();
	private boolean gattOperationInProgress = false;

	public TiBleCentralOperationManager(Context context, TiBLECentralManagerProxy centralManagerProxy,
										TiBLEPeripheralProxy peripheralProxy, boolean autoConnect)
	{
		this.context = context;
		this.centralManagerProxy = centralManagerProxy;
		this.peripheralProxy = peripheralProxy;
		this.autoConnect = autoConnect;
	}

	// Queues `operation` behind anything already in flight on this connection's BluetoothGatt, or
	// runs it immediately if nothing is. `operation` must call completeGattOperation() exactly
	// once - synchronously, if it determines up front that no BluetoothGattCallback method will
	// ever fire for it (e.g. a bluetoothGatt.writeCharacteristic() call returning false), or from
	// that eventual callback otherwise.
	private void enqueueGattOperation(Runnable operation)
	{
		boolean runNow;
		synchronized (gattQueueLock) {
			gattOperationQueue.add(operation);
			runNow = !gattOperationInProgress;
			gattOperationInProgress = true;
		}
		if (runNow) {
			operation.run();
		}
	}

	// Called exactly once per queued operation, from wherever that operation's outcome becomes
	// known - starts the next queued operation, if any.
	private void completeGattOperation()
	{
		Runnable next;
		synchronized (gattQueueLock) {
			gattOperationQueue.poll();
			next = gattOperationQueue.peek();
			gattOperationInProgress = (next != null);
		}
		if (next != null) {
			next.run();
		}
	}

	// A disconnected connection's BluetoothGatt won't fire any more callbacks - anything still
	// queued would otherwise stall forever (and would be stale/pointless to run regardless).
	private void clearGattOperationQueue()
	{
		synchronized (gattQueueLock) {
			gattOperationQueue.clear();
			gattOperationInProgress = false;
		}
	}

	public void initiateConnectionWithPeripheral()
	{
		bluetoothGatt = peripheralProxy.getDevice().connectGatt(context, autoConnect, new BluetoothGattCallback() {
			@Override
			public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState)
			{
				super.onConnectionStateChange(gatt, status, newState);
				handleOnConnectionStateChanged(status, newState);
			}

			@Override
			public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status)
			{
				super.onReadRemoteRssi(gatt, rssi, status);
				handleOnReadRemoteRssi(rssi, status);
				completeGattOperation();
			}

			@Override
			public void onServicesDiscovered(BluetoothGatt gatt, int status)
			{
				super.onServicesDiscovered(gatt, status);
				handleOnServicesDiscovered(gatt, status);
				completeGattOperation();
			}

			@Override
			public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status)
			{
				super.onCharacteristicRead(gatt, characteristic, status);
				handleOnCharacteristicRead(characteristic, status);
				completeGattOperation();
			}

			@Override
			public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic)
			{
				super.onCharacteristicChanged(gatt, characteristic);
				// An unsolicited notify/indicate push from the peripheral, not a response to our
				// own readValueForCharacteristic() call - never queued via enqueueGattOperation,
				// so must NOT call completeGattOperation() (that would advance the queue based on
				// an event unrelated to whatever operation is actually in flight).
				handleOnCharacteristicRead(characteristic, BluetoothGatt.GATT_SUCCESS);
			}

			@Override
			public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic,
											  int status)
			{
				super.onCharacteristicWrite(gatt, characteristic, status);
				handleOnCharacteristicWrite(characteristic, status);
				completeGattOperation();
			}

			@Override
			public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status)
			{
				super.onDescriptorRead(gatt, descriptor, status);
				handleOnDescriptorRead(descriptor, status);
				completeGattOperation();
			}

			@Override
			public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status)
			{
				super.onDescriptorWrite(gatt, descriptor, status);
				handleOnDescriptorWrite(descriptor, status);
				completeGattOperation();
			}

		});
		connectionState = ConnectionState.Connecting;
	}

	private void handleOnConnectionStateChanged(int status, int newState)
	{
		switch (newState) {
			case BluetoothProfile.STATE_CONNECTED:
				Log.d(LCAT, "connected to peripheral: name- " + peripheralProxy.name() + ", address- "
								+ peripheralProxy.address());
				connectionState = ConnectionState.Connected;
				KrollDict dict = new KrollDict();
				dict.put(KeysConstants.peripheral.name(), peripheralProxy);
				centralManagerProxy.fireEvent(KeysConstants.didConnectPeripheral.name(), dict);
				break;
			case BluetoothProfile.STATE_DISCONNECTED:
				handleDisconnection(status);
				break;
			default:
				Log.d(LCAT, "handleOnConnectionStateChanged(): central connection state value= " + newState);
		}
	}

	private void handleOnReadRemoteRssi(int rssi, int status)
	{
		Log.d(LCAT, "handleOnReadRemoteRssi(): rssi =  " + rssi
						+ "status = " + (status == BluetoothGatt.GATT_SUCCESS ? "success" : "fail"));
		KrollDict dict = new KrollDict();
		dict.put(KeysConstants.sourcePeripheral.name(), peripheralProxy);
		dict.put(KeysConstants.rssi.name(), rssi);
		if (status != BluetoothGatt.GATT_SUCCESS) {
			String errorMessage = "failed to read remote rssi";
			dict.put(KeysConstants.errorCode.name(), status);
			dict.put(KeysConstants.errorDescription.name(), getErrorDescriptionMessage(status, errorMessage));
		}
		peripheralProxy.fireEvent(KeysConstants.didReadRSSI.name(), dict);
	}

	private void handleOnServicesDiscovered(BluetoothGatt gatt, int status)
	{
		Log.d(LCAT, "handleOnServicesDiscovered(): discover service operation result = "
						+ (status == BluetoothGatt.GATT_SUCCESS ? "success" : "fail"));

		KrollDict dict = new KrollDict();
		dict.put(KeysConstants.sourcePeripheral.name(), peripheralProxy);

		if (status == BluetoothGatt.GATT_SUCCESS) {
			peripheralProxy.addServices(gatt.getServices());
		} else {
			String errorMessage = "failed to discover services for peripheral name/address- " + peripheralProxy.name()
								  + " / " + peripheralProxy.address();
			dict.put(KeysConstants.errorCode.name(), status);
			dict.put(KeysConstants.errorDescription.name(), getErrorDescriptionMessage(status, errorMessage));
		}

		peripheralProxy.fireEvent(KeysConstants.didDiscoverServices.name(), dict);
	}

	private void handleOnCharacteristicRead(BluetoothGattCharacteristic characteristic, int status)
	{
		TiBLECharacteristicProxy characteristicProxy = new TiBLECharacteristicProxy(characteristic);
		KrollDict dict = new KrollDict();
		dict.put(KeysConstants.sourcePeripheral.name(), peripheralProxy);
		dict.put(KeysConstants.characteristic.name(), characteristicProxy);
		if (status == BluetoothGatt.GATT_SUCCESS) {
			Log.d(LCAT, "handleOnCharacteristicRead(): characteristic- " + characteristic.getUuid().toString()
							+ " read successful.");
			dict.put(KeysConstants.value.name(), characteristicProxy.value());
		} else {
			Log.d(LCAT, "handleOnCharacteristicRead(): characteristic- " + characteristic.getUuid().toString()
							+ " read failed.");
			String errorMessage = "failed to read the characteristic for peripheral name/address- "
								  + peripheralProxy.name() + " / " + peripheralProxy.address();
			dict.put(KeysConstants.errorCode.name(), status);
			dict.put(KeysConstants.errorDescription.name(), getErrorDescriptionMessage(status, errorMessage));
		}

		peripheralProxy.fireEvent(KeysConstants.didUpdateValueForCharacteristic.name(), dict);
	}

	private void handleOnCharacteristicWrite(BluetoothGattCharacteristic characteristic, int status)
	{
		KrollDict dict = new KrollDict();
		dict.put(KeysConstants.sourcePeripheral.name(), peripheralProxy);
		dict.put(KeysConstants.characteristic.name(), new TiBLECharacteristicProxy(characteristic));
		if (status != BluetoothGatt.GATT_SUCCESS) {
			Log.d(LCAT, "handleOnCharacteristicWrite(): characteristic- " + characteristic.getUuid().toString()
							+ " write failed.");
			String errorMessage = "failed to write value on characteristic for peripheral name/address- "
								  + peripheralProxy.name() + " / " + peripheralProxy.address();
			dict.put(KeysConstants.errorCode.name(), status);
			dict.put(KeysConstants.errorDescription.name(), getErrorDescriptionMessage(status, errorMessage));
		} else {
			Log.d(LCAT, "handleOnCharacteristicWrite(): characteristic- " + characteristic.getUuid().toString()
							+ " write successful.");
		}

		peripheralProxy.fireEvent(KeysConstants.didWriteValueForCharacteristic.name(), dict);
	}

	private void handleOnDescriptorRead(BluetoothGattDescriptor descriptor, int status)
	{
		KrollDict dict = new KrollDict();
		dict.put(KeysConstants.sourcePeripheral.name(), peripheralProxy);
		dict.put(KeysConstants.descriptor.name(), new TiBLEDescriptorProxy(descriptor));
		if (status != BluetoothGatt.GATT_SUCCESS) {
			Log.d(LCAT, "handleOnDescriptorRead(): descriptor- " + descriptor.getUuid().toString() + " read failed.");
			String errorMessage = "failed to read the descriptor for peripheral name/address- " + peripheralProxy.name()
								  + " / " + peripheralProxy.address();
			dict.put(KeysConstants.errorCode.name(), status);
			dict.put(KeysConstants.errorDescription.name(), getErrorDescriptionMessage(status, errorMessage));
		} else {
			Log.d(LCAT,
				  "handleOnDescriptorRead(): descriptor- " + descriptor.getUuid().toString() + " read successful.");
		}

		peripheralProxy.fireEvent(KeysConstants.didUpdateValueForDescriptor.name(), dict);
	}

	private void handleOnDescriptorWrite(BluetoothGattDescriptor descriptor, int status)
	{
		KrollDict dict = new KrollDict();
		dict.put(KeysConstants.sourcePeripheral.name(), peripheralProxy);
		dict.put(KeysConstants.descriptor.name(), new TiBLEDescriptorProxy(descriptor));
		if (status != BluetoothGatt.GATT_SUCCESS) {
			Log.d(LCAT, "handleOnDescriptorWrite(): descriptor- " + descriptor.getUuid().toString() + " write failed.");
			String errorMessage = "failed to write value on descriptor for peripheral name/address- "
								  + peripheralProxy.name() + " / " + peripheralProxy.address();
			dict.put(KeysConstants.errorCode.name(), status);
			dict.put(KeysConstants.errorDescription.name(), getErrorDescriptionMessage(status, errorMessage));
		} else {
			Log.d(LCAT,
				  "handleOnDescriptorWrite(): descriptor- " + descriptor.getUuid().toString() + " write successful.");
		}

		peripheralProxy.fireEvent(KeysConstants.didWriteValueForDescriptor.name(), dict);
	}

	public void cancelPeripheralConnection()
	{
		connectionState = ConnectionState.Disconnecting;
		bluetoothGatt.disconnect();
	}

	public boolean isConnected()
	{
		return connectionState == ConnectionState.Connected;
	}

	public void readRSSI()
	{
		enqueueGattOperation(() -> {
			boolean isReadInitiated = bluetoothGatt.readRemoteRssi();
			if (!isReadInitiated) {
				// No previous behaviour to preserve here (readRemoteRssi()'s return value was
				// never checked before) - just unblock the queue, since onReadRemoteRssi will
				// never fire for this call.
				completeGattOperation();
			}
		});
	}

	public void requestConnectionPriority(int priority)
	{
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			bluetoothGatt.requestConnectionPriority(priority);
		} else {
			Log.d(
				LCAT,
				"requestConnectionPriority(): This functionality is supported on Android os version LOLLIPOP and onwards.");
		}
	}

	public void discoverServices()
	{
		enqueueGattOperation(() -> {
			boolean isDiscoveryInitiated = bluetoothGatt.discoverServices();
			if (!isDiscoveryInitiated) {
				// No previous behaviour to preserve here (discoverServices()'s return value was
				// never checked before) - just unblock the queue, since onServicesDiscovered will
				// never fire for this call.
				completeGattOperation();
			}
		});
	}

	public void discoverIncludedServices(TiBLEServiceProxy serviceProxy)
	{
		// In Android, the all included services have already been discovered as part of the discoverServices operation.
		// so directly fire corresponding result event for it.
		KrollDict dict = new KrollDict();
		dict.put(KeysConstants.sourcePeripheral.name(), peripheralProxy);
		dict.put(KeysConstants.service.name(), serviceProxy);
		peripheralProxy.fireEvent(KeysConstants.didDiscoverIncludedServices.name(), dict);
	}

	public void discoverCharacteristics(TiBLEServiceProxy serviceProxy)
	{
		// In Android, all characteristics have already been discovered as part of the discoverServices operation.
		// so directly fire corresponding result event for it.
		KrollDict dict = new KrollDict();
		dict.put(KeysConstants.sourcePeripheral.name(), peripheralProxy);
		dict.put(KeysConstants.service.name(), serviceProxy);
		peripheralProxy.fireEvent(KeysConstants.didDiscoverCharacteristics.name(), dict);
	}

	public void discoverDescriptorsForCharacteristic(TiBLECharacteristicProxy characteristicProxy)
	{
		// In Android, all descriptors have already been discovered as part of the discoverServices operation.
		// so directly fire corresponding result event for it.
		KrollDict dict = new KrollDict();
		dict.put(KeysConstants.sourcePeripheral.name(), peripheralProxy);
		dict.put(KeysConstants.characteristic.name(), characteristicProxy);
		peripheralProxy.fireEvent(KeysConstants.didDiscoverDescriptorsForCharacteristics.name(), dict);
	}

	public void handleDisconnection(int status)
	{
		ConnectionState olderState = connectionState;
		connectionState = ConnectionState.Disconnected;
		KrollDict dict = new KrollDict();
		dict.put(KeysConstants.peripheral.name(), peripheralProxy);

		if (olderState == ConnectionState.Connected || olderState == ConnectionState.Disconnecting) {
			Log.d(LCAT, "handleDisconnection(): disconnected to peripheral: name- " + peripheralProxy.name()
							+ ", address- " + peripheralProxy.address());
			if (status != BluetoothGatt.GATT_SUCCESS) {
				String errorMessage = "connection disconnected with the peripheral.";
				dict.put(KeysConstants.errorCode.name(), status);
				dict.put(KeysConstants.errorDescription.name(), getErrorDescriptionMessage(status, errorMessage));
			}
			centralManagerProxy.fireEvent(KeysConstants.didDisconnectPeripheral.name(), dict);
		} else {
			Log.d(LCAT, "handleDisconnection(): failed to connect with peripheral: name- " + peripheralProxy.name()
							+ ", address- " + peripheralProxy.address());
			String errorMessage = "failed to connect with peripheral.";
			dict.put(KeysConstants.errorCode.name(), status);
			dict.put(KeysConstants.errorDescription.name(), getErrorDescriptionMessage(status, errorMessage));
			centralManagerProxy.fireEvent(KeysConstants.didFailToConnectPeripheral.name(), dict);
		}

		bluetoothGatt.close();
		clearGattOperationQueue();
		centralManagerProxy.stopAndUnbindService();
	}

	public void readValueForCharacteristic(TiBLECharacteristicProxy characteristicProxy)
	{
		enqueueGattOperation(() -> {
			boolean isReadInitiated = bluetoothGatt.readCharacteristic(characteristicProxy.getCharacteristic());
			Log.d(LCAT, "readValueForCharacteristic(): characteristic- " + characteristicProxy.uuid()
							+ " read initiation status- ." + isReadInitiated);
			if (!isReadInitiated) {
				KrollDict dict = new KrollDict();
				String errorMessage = "failed to initiate reading characteristic for peripheral name/address- "
									  + peripheralProxy.name() + " / " + peripheralProxy.address();
				dict.put(KeysConstants.sourcePeripheral.name(), peripheralProxy);
				dict.put(KeysConstants.characteristic.name(), characteristicProxy);
				dict.put(KeysConstants.errorCode.name(), BluetoothGatt.GATT_FAILURE);
				dict.put(KeysConstants.errorDescription.name(),
						 getErrorDescriptionMessage(BluetoothGatt.GATT_FAILURE, errorMessage));
				peripheralProxy.fireEvent(KeysConstants.didUpdateValueForCharacteristic.name(), dict);
				// onCharacteristicRead will never fire for this call - unblock the queue here.
				completeGattOperation();
			}
		});
	}

	public void writeValueForCharacteristic(TiBLECharacteristicProxy charProxy, byte[] buffer, int writeType)
	{
		enqueueGattOperation(() -> {
			charProxy.getCharacteristic().setWriteType(writeType);
			charProxy.getCharacteristic().setValue(buffer);
			boolean isWritingInitiated = bluetoothGatt.writeCharacteristic(charProxy.getCharacteristic());
			Log.d(LCAT, "writeValueForCharacteristic(): characteristic- " + charProxy.uuid() + " write initiation status- ."
							+ isWritingInitiated);
			if (!isWritingInitiated) {
				KrollDict dict = new KrollDict();
				String errorMessage = "failed to initiate writing value on characteristic for peripheral name/address- "
									  + peripheralProxy.name() + " / " + peripheralProxy.address();
				dict.put(KeysConstants.sourcePeripheral.name(), peripheralProxy);
				dict.put(KeysConstants.characteristic.name(), charProxy);
				dict.put(KeysConstants.errorCode.name(), BluetoothGatt.GATT_FAILURE);
				dict.put(KeysConstants.errorDescription.name(),
						 getErrorDescriptionMessage(BluetoothGatt.GATT_FAILURE, errorMessage));
				peripheralProxy.fireEvent(KeysConstants.didWriteValueForCharacteristic.name(), dict);
				// onCharacteristicWrite will never fire for this call - unblock the queue here.
				completeGattOperation();
			}
		});
	}

	public void readValueForDescriptor(TiBLEDescriptorProxy descriptorProxy)
	{
		enqueueGattOperation(() -> {
			boolean isReadInitiated = bluetoothGatt.readDescriptor(descriptorProxy.getDescriptor());
			Log.d(LCAT, "readValueForDescriptor(): descriptor- " + descriptorProxy.uuid() + " read initiation status- ."
							+ isReadInitiated);
			if (!isReadInitiated) {
				KrollDict dict = new KrollDict();
				String errorMessage = "failed to initiate reading value on descriptor for peripheral name/address- "
									  + peripheralProxy.name() + " / " + peripheralProxy.address();
				dict.put(KeysConstants.sourcePeripheral.name(), peripheralProxy);
				dict.put(KeysConstants.descriptor.name(), descriptorProxy);
				dict.put(KeysConstants.errorCode.name(), BluetoothGatt.GATT_FAILURE);
				dict.put(KeysConstants.errorDescription.name(),
						 getErrorDescriptionMessage(BluetoothGatt.GATT_FAILURE, errorMessage));
				peripheralProxy.fireEvent(KeysConstants.didUpdateValueForDescriptor.name(), dict);
				// onDescriptorRead will never fire for this call - unblock the queue here.
				completeGattOperation();
			}
		});
	}

	public void writeValueForDescriptor(TiBLEDescriptorProxy descriptorProxy, byte[] buffer)
	{
		enqueueGattOperation(() -> {
			BluetoothGattDescriptor descriptor = descriptorProxy.getDescriptor();
			descriptor.setValue(buffer);
			boolean isWritingInitiated = bluetoothGatt.writeDescriptor(descriptor);
			Log.d(LCAT, "writeValueForDescriptor(): descriptor- " + descriptorProxy.uuid() + " write initiation status- ."
							+ isWritingInitiated);
			if (!isWritingInitiated) {
				KrollDict dict = new KrollDict();
				String errorMessage = "failed to initiate writing value on descriptor for peripheral name/address- "
									  + peripheralProxy.name() + " / " + peripheralProxy.address();
				dict.put(KeysConstants.sourcePeripheral.name(), peripheralProxy);
				dict.put(KeysConstants.descriptor.name(), descriptorProxy);
				dict.put(KeysConstants.errorCode.name(), BluetoothGatt.GATT_FAILURE);
				dict.put(KeysConstants.errorDescription.name(),
						 getErrorDescriptionMessage(BluetoothGatt.GATT_FAILURE, errorMessage));
				peripheralProxy.fireEvent(KeysConstants.didWriteValueForDescriptor.name(), dict);
				// onDescriptorWrite will never fire for this call - unblock the queue here.
				completeGattOperation();
			}
		});
	}

	public void subscribeToCharacteristic(TiBLECharacteristicProxy charProxy, String descriptorUUID,
										  BufferProxy enableValue)
	{
		enqueueGattOperation(() -> {
			int properties = charProxy.getCharacteristic().getProperties();
			if ((properties & PROPERTY_NOTIFY) <= 0 && (properties & PROPERTY_INDICATE) <= 0) {
				String errorDescription = String.format(
					"cannot subscribe as characteristic- %s does not have notify/indicate property.", charProxy.uuid());
				Log.e(LCAT, "subscribeToCharacteristic(): " + errorDescription);
				fireFailedUpdateNotificationStateEvent(charProxy, errorDescription, true);
				completeGattOperation();
				return;
			}

			String descUuid = descriptorUUID != null && !descriptorUUID.isEmpty()
								  ? descriptorUUID
								  : UUID_CLIENT_CHARACTERISTIC_CONFIGURATION;
			if (charProxy.getCharacteristic().getDescriptor(UUID.fromString(descUuid)) == null) {
				String errorDescription = String.format("cannot subscribe as CCC descriptor- %s not found", descUuid);
				Log.e(LCAT, "subscribeToCharacteristic(): " + errorDescription);
				fireFailedUpdateNotificationStateEvent(charProxy, errorDescription, true);
				completeGattOperation();
				return;
			}

			boolean setCharNotificationSuccessful =
				bluetoothGatt.setCharacteristicNotification(charProxy.getCharacteristic(), true);
			if (!setCharNotificationSuccessful) {
				String errorDescription = String.format(
					"cannot subscribe as setCharacteristicNotification for characteristic- %s failed.", charProxy.uuid());
				Log.e(LCAT, "subscribeToCharacteristic(): " + errorDescription);
				fireFailedUpdateNotificationStateEvent(charProxy, errorDescription, true);
				completeGattOperation();
				return;
			}

			// Only this branch (a real writeDescriptor() call) leaves an async GATT operation in
			// flight - onDescriptorWrite completes the queue for it. Every other path here
			// (the three early returns above, and falling through to the success event below
			// when no descriptorUUID was given at all) resolves synchronously and must complete
			// the queue itself, since no BluetoothGattCallback method will ever follow for it.
			if (descriptorUUID != null && !descriptorUUID.isEmpty()) {
				BluetoothGattDescriptor descriptor =
					charProxy.getCharacteristic().getDescriptor(UUID.fromString(descriptorUUID));
				descriptor.setValue(enableValue.getBuffer());
				boolean isWrite = bluetoothGatt.writeDescriptor(descriptor);
				if (!isWrite) {
					String errorDescription = String.format(
						"cannot subscribe as writeDescriptor failed for descriptor- %s .", descriptorUUID);
					Log.e(LCAT, "subscribeToCharacteristic(): " + errorDescription);
					fireFailedUpdateNotificationStateEvent(charProxy, errorDescription, true);
					completeGattOperation();
					return;
				}
				Log.d(LCAT, "subscribeToCharacteristic(): subscribe successful");
				return;
			}
			Log.d(LCAT, "subscribeToCharacteristic(): subscribe successful");

			KrollDict dict = new KrollDict();
			dict.put(KeysConstants.sourcePeripheral.name(), peripheralProxy);
			dict.put(KeysConstants.characteristic.name(), charProxy);
			dict.put(KeysConstants.isSubscribed.name(), true);
			peripheralProxy.fireEvent(KeysConstants.didUpdateNotificationStateForCharacteristics.name(), dict);
			completeGattOperation();
		});
	}

	public void unsubscribeFromCharacteristic(TiBLECharacteristicProxy charProxy, String descriptorUUID,
											  BufferProxy disableValue)
	{
		enqueueGattOperation(() -> {
			int properties = charProxy.getCharacteristic().getProperties();
			if ((properties & PROPERTY_NOTIFY) <= 0 && (properties & PROPERTY_INDICATE) <= 0) {
				String errorDescription = String.format(
					"cannot unsubscribe as Characteristic- %s does not have notify or indicate property", charProxy.uuid());
				Log.e(LCAT, "unsubscribeFromCharacteristic(): " + errorDescription);
				fireFailedUpdateNotificationStateEvent(charProxy, errorDescription, false);
				completeGattOperation();
				return;
			}

			String descUuid = descriptorUUID != null && !descriptorUUID.isEmpty()
								  ? descriptorUUID
								  : UUID_CLIENT_CHARACTERISTIC_CONFIGURATION;
			if (charProxy.getCharacteristic().getDescriptor(UUID.fromString(descUuid)) == null) {
				String errorDescription = String.format("cannot unsubscribe as CCC descriptor- %s not found", descUuid);
				Log.e(LCAT, "unsubscribeFromCharacteristic(): " + errorDescription);
				fireFailedUpdateNotificationStateEvent(charProxy, errorDescription, false);
				completeGattOperation();
				return;
			}

			boolean setCharNotificationSuccessful =
				bluetoothGatt.setCharacteristicNotification(charProxy.getCharacteristic(), false);
			if (!setCharNotificationSuccessful) {
				String errorDescription = String.format(
					"cannot unsubscribe as setCharacteristicNotification for characteristic- %s failed.", charProxy.uuid());
				Log.e(LCAT, "unsubscribeFromCharacteristic(): " + errorDescription);
				fireFailedUpdateNotificationStateEvent(charProxy, errorDescription, false);
				completeGattOperation();
				return;
			}

			// See subscribeToCharacteristic()'s identical comment above.
			if (descriptorUUID != null && !descriptorUUID.isEmpty()) {
				BluetoothGattDescriptor descriptor =
					charProxy.getCharacteristic().getDescriptor(UUID.fromString(descriptorUUID));
				descriptor.setValue(disableValue.getBuffer());
				boolean isWrite = bluetoothGatt.writeDescriptor(descriptor);
				if (!isWrite) {
					String errorDescription = String.format(
						"cannot unsubscribe as writeDescriptor failed for descriptor- %s .", descriptorUUID);
					Log.e(LCAT, "unsubscribeFromCharacteristic(): " + errorDescription);
					fireFailedUpdateNotificationStateEvent(charProxy, errorDescription, false);
					completeGattOperation();
					return;
				}
				Log.d(LCAT, "unsubscribeToCharacteristic(): unsubscribe successful");
				return;
			}
			Log.d(LCAT, "unsubscribeToCharacteristic(): unsubscribe successful");

			KrollDict dict = new KrollDict();
			dict.put(KeysConstants.sourcePeripheral.name(), peripheralProxy);
			dict.put(KeysConstants.characteristic.name(), charProxy);
			dict.put(KeysConstants.isSubscribed.name(), false);
			peripheralProxy.fireEvent(KeysConstants.didUpdateNotificationStateForCharacteristics.name(), dict);
			completeGattOperation();
		});
	}

	private void fireFailedUpdateNotificationStateEvent(TiBLECharacteristicProxy charProxy, String errorDescription,
														boolean isSubscribed)
	{
		KrollDict dict = new KrollDict();
		dict.put(KeysConstants.sourcePeripheral.name(), peripheralProxy);
		dict.put(KeysConstants.characteristic.name(), charProxy);
		dict.put(KeysConstants.isSubscribed.name(), isSubscribed);
		dict.put(KeysConstants.errorCode.name(), BluetoothGatt.GATT_FAILURE);
		dict.put(KeysConstants.errorDescription.name(),
				 getErrorDescriptionMessage(BluetoothGatt.GATT_FAILURE, errorDescription));
		peripheralProxy.fireEvent(KeysConstants.didUpdateNotificationStateForCharacteristics.name(), dict);
	}

	private String getErrorDescriptionMessage(int status, String message)
	{
		String errorMessage;
		switch (status) {
			case BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION:
				errorMessage = "Insufficient authentication for a given operation.";
				break;
			case BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH:
				errorMessage = "The write operation exceeds the maximum length of the attribute.";
				break;
			case BluetoothGatt.GATT_INVALID_OFFSET:
				errorMessage = "The read or write operation was requested with an invalid offset.";
				break;
			case BluetoothGatt.GATT_READ_NOT_PERMITTED:
				errorMessage = "The read operation is not permitted.";
				break;
			case BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED:
				errorMessage = "The given request is not supported.";
				break;
			case BluetoothGatt.GATT_WRITE_NOT_PERMITTED:
				errorMessage = "The write operation is not permitted.";
				break;
			case BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION:
				errorMessage = "Insufficient encryption for a given operation.";
				break;
			case BluetoothGatt.GATT_FAILURE:
			default:
				if (message == null) {
					errorMessage = "Failed to perform this operation.";
				} else {
					errorMessage = message;
				}
				break;
		}
		return errorMessage;
	}

	public enum ConnectionState { New, Connecting, Connected, Disconnecting, Disconnected }
}
