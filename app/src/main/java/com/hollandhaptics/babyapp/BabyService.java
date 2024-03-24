/**************************************************************************
 *
 * BabyApp              BabyService
 *
 * Version:             1.0.1
 *
 * The BabyApp starts an Android background Service which measures
 * the current sound level as registered by the microphone of an Android Smartphone.
 * It will start an audio recording if the sound level exceeds a preset value.
 * Consequently, the app will send or upload the recorded audio files to a
 * predefined LAMP server running a simple PHP script. This PHP script saves
 * the audio file in a directory on the lamp server.
 *
 * Authors:             johny.gorissen@hollandhaptics.com
 *                      r.a.van.emden@vu.nl / robinvanemden@gmail.com
 *
 * Attribution:         Hans IJzerman, https://sites.google.com/site/hijzerman/
 *                      Holland Haptics, http://myfrebble.com/
 *                      Robin van Emden, http://www.pavlov.io/
 *
 * License:             Attribution-ShareAlike 4.0 International
 *                      http://creativecommons.org/licenses/by-sa/4.0/
 *
 **************************************************************************/

package com.hollandhaptics.babyapp;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;

import android.annotation.SuppressLint;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.TaskStackBuilder;
import android.content.Context;
import android.content.Intent;
import android.media.MediaRecorder;
import android.media.MediaRecorder.OnErrorListener;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

public class BabyService extends Service implements Runnable, OnErrorListener {

    private String TAG = "BabyService";

    private static final double miniFreeStorageSpacePercent = 10d;
    private static final double tolerancePerOne = 0.001d;

    private static final double amplitudeThreshold = 1000; // maxAmp: 2^16/2

    private static final int numberOfRetries = 60;
    private static final long serviceRunnableInterval = 2000;

    private Handler handler;
    private MediaRecorder recorder;

    private boolean hasRecorded;

    private int attempt;
    private int serverResponseCode;

    private File[] filesForUploading;

    private String fileExt;
    private String dateFormat;
    private String audioFileNameFormat;
    private String upLoadServerUri;
    private String extStoragePath;
    private String currentFilename;

    public BabyService() {
	attempt = 0;
	serverResponseCode = 0;
	dateFormat = "dd-MM-yyyy_HH-mm-ss";
	audioFileNameFormat = "%s/%s%s";
	hasRecorded = false;
	extStoragePath = null;
	currentFilename = null;
	fileExt = null;
	handler = null;
	recorder = null;
	upLoadServerUri = null;
    }

    /**
     * @brief Entry point of the Service.
     */
    @SuppressLint("HardwareIds")
    @Override
    public void onCreate() {
	super.onCreate();
	Log.d(TAG, "BabyService Created");
	upLoadServerUri = getResources().getString(R.string.file_upload_url);
	String appFolderName = "Mensajes";
	File storageDir = Environment.getExternalStorageDirectory();
	String storageDirPath = storageDir.getAbsolutePath();
	File recordingsFolder = new File(storageDirPath, appFolderName);
	extStoragePath = recordingsFolder.toString();
	if (recordingsFolder.exists() || recordingsFolder.mkdir()) {
	    Log.d(TAG, String.format("%s created/already exists", extStoragePath));
	    recorder = new MediaRecorder();
	} else {
	    Log.e(TAG, "Directory NOT created");
	    stopSelf();
	}
    }

    @Override
    public void run() {
	if (isExternalStorageWritable() == false) {
	    Log.d(TAG, "No permission to write to external storage");
	    stopSelf();
	    return;
	}
	if (IsThereStorageSpace() == false) {
	    String notificationStr = "(Almost) out of storage space";
	    Log.d(TAG, notificationStr);
	    notifyUser(notificationStr);
	    recorder.stop();
	    if (hasRecorded == false) {
		deleteAudioFile(currentFilename);
	    }
	    recorder.reset();
	    stopSelf();// StartRecordings();
	    return;
	}
	String format = "(%d/%d) Amplitude: %d";
	int amplitude = recorder.getMaxAmplitude();
	Log.d(TAG, String.format(format, attempt, numberOfRetries, amplitude));
	if (amplitude > amplitudeThreshold) {
	    Log.d(TAG, "Continue recording.");
	    hasRecorded = true;
	    attempt = 0;
	} else {
	    if (attempt >= numberOfRetries) {
		Log.d(TAG, "Stop recording for now.");
		recorder.stop();
		if (hasRecorded = false) {
		    deleteAudioFile(currentFilename);
		}
		recorder.reset();
		StartRecordings();
	    } else {
		attempt++;
	    }
	}
	handler.postDelayed(this, serviceRunnableInterval);
    }

    /**
     * @param intent  intent
     * @param flags   flags
     * @param startId startId
     * @return Returns START_STICKY.
     * @brief Start point of service
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
	Log.d(TAG, "BabyService onStartCommand");
	handler = new Handler();
	StartRecordings();
	handler.postDelayed(this, serviceRunnableInterval);
	return START_STICKY; // running until "FORCE_STOP"
    }

    /**
     * @brief Configures the MediaRecorder for a new recording. This method is
     *        called by StartRecordings() before starting the MediaRecorder.
     */
    private void ConfigureRecorder() {
	Log.d(TAG, "Configuring the MediaRecorder");

	int audioSource = MediaRecorder.AudioSource.MIC, outputFormat = MediaRecorder.OutputFormat.MPEG_4,
		audioEncoder = MediaRecorder.AudioEncoder.AAC, sampleRate = 22050, audioChannelsQuantity = 1,
		encodingBitRate = 128000;

	@SuppressLint("SimpleDateFormat")
	SimpleDateFormat formatter = new SimpleDateFormat(dateFormat);
	Date now = new Date();

	switch (outputFormat) {
	case MediaRecorder.OutputFormat.MPEG_4:
	    fileExt = ".m4a";
	    break;
	case MediaRecorder.OutputFormat.AAC_ADTS:
	    fileExt = ".aac";
	    break;
	}

	recorder.setAudioSource(audioSource);
	recorder.setOutputFormat(outputFormat);
	recorder.setAudioEncoder(audioEncoder);
	recorder.setAudioSamplingRate(sampleRate);
	recorder.setAudioChannels(audioChannelsQuantity);
	recorder.setAudioEncodingBitRate(encodingBitRate);

	currentFilename = formatter.format(now);
	recorder.setOutputFile(String.format(audioFileNameFormat, extStoragePath, currentFilename, fileExt));
	try {
	    recorder.prepare();
	} catch (IOException e) {
	    Log.e(TAG, "prepare() failed");
	}
    }

    /**
     * @brief Configure the MediaRecorder and start a new recording.
     */
    private void StartRecordings() {
	Log.d(TAG, "Starting a new recording.");
	ConfigureRecorder();
	hasRecorded = false;
	attempt = 0;
	recorder.start();
    }

    /**
     * @param filename The filename of the file.
     * @brief Deletes an audio file with the given filename.
     */
    private void deleteAudioFile(String filename) {
	String audioFileNameStr = String.format(audioFileNameFormat, extStoragePath, filename, fileExt),
		logDeleteFormat = "Delete %s: %s";
	File file = new File(audioFileNameStr);
	if (file.delete())
	    Log.d(TAG, String.format(logDeleteFormat, audioFileNameFormat, "OK"));
	else
	    Log.e(TAG, String.format(logDeleteFormat, audioFileNameFormat, "FAILED"));
    }

    /**
     * @param msgStr The message to show.
     * @brief Makes a notification for the user
     */
    private void notifyUser(String msgStr) {
	String contentTitle = getResources().getText(R.string.app_name).toString();
	int pendingIntentFlag = PendingIntent.FLAG_UPDATE_CURRENT, id = 0;
	String notifServiceFlag = Context.NOTIFICATION_SERVICE;

	NotificationCompat.Builder notifBuilder = new NotificationCompat.Builder(this)
		.setSmallIcon(R.mipmap.ic_launcher).setContentTitle(contentTitle).setContentText(msgStr);

	Intent resultIntent = new Intent(this, MainActivity.class);

	TaskStackBuilder stkBuilder = TaskStackBuilder.create(this);

	stkBuilder.addParentStack(MainActivity.class);
	stkBuilder.addNextIntent(resultIntent);
	PendingIntent pendingIntent = stkBuilder.getPendingIntent(id, pendingIntentFlag);
	notifBuilder.setContentIntent(pendingIntent);
	NotificationManager mNotificationManager = (NotificationManager) getSystemService(notifServiceFlag);

	mNotificationManager.notify(id, notifBuilder.build());
    }

    /**
     * @brief Handles when the Service stops.
     */
    @Override
    public void onDestroy() {
	Log.d(TAG, "BabyService Destroyed");
	super.onDestroy();
	recorder.reset();
	recorder.release();
    }

    /**
     * @param intent An Intent.
     * @return A new BabyServiceBinder object.
     * @brief Binds the Service.
     */
    @Override
    public IBinder onBind(Intent intent) {
	return new BabyServiceBinder(this);
    }

    /**
     * @brief Searches for audio files in the app's folder and starts uploading them
     *        to the server.
     */
    public void uploadAudioFiles() {

	filesForUploading = new File(extStoragePath).listFiles();
	new Thread(new Runnable() {
	    public void run() {
		// uploadFile(uploadFilePath + "" + uploadFileName);
		for (File uploadFile : filesForUploading) {
		    uploadFile(uploadFile);
		}
	    }
	}).start();
    }

    /**
     * @param sourceFile The file to upload.
     * @return int The server's response code.
     * @brief Uploads a file to the server. Is called by uploadAudioFiles().
     */
    public int uploadFile(File sourceFile) {
	HttpURLConnection conn;
	DataOutputStream dos;
	String lineEnd = "\r\n";
	String twoHyphens = "--";
	String boundary = "*****";
	int bytesRead, bytesAvailable, bufferSize;
	byte[] buffer;
	int maxBufferSize = 1024 * 1024;
	String sourceFileUri = sourceFile.getAbsolutePath();

	if (!sourceFile.isFile()) {
	    Log.e("uploadFile", "Source File does not exist :" + sourceFileUri);
	    return 0;
	} else {
	    try {
		// open a URL connection to the Servlet
		FileInputStream fileInputStream = new FileInputStream(sourceFile);
		URL url = new URL(upLoadServerUri);

		// Open a HTTP connection to the URL
		conn = (HttpURLConnection) url.openConnection();
		conn.setDoInput(true); // Allow Inputs
		conn.setDoOutput(true); // Allow Outputs
		conn.setUseCaches(false); // Don't use a Cached Copy
		conn.setRequestMethod("POST");
		conn.setRequestProperty("Connection", "Keep-Alive");
		conn.setRequestProperty("ENCTYPE", "multipart/form-data");
		conn.setRequestProperty("Content-Type", "multipart/form-data;boundary=" + boundary);
		conn.setRequestProperty("fileToUpload", sourceFileUri);

		dos = new DataOutputStream(conn.getOutputStream());

		dos.writeBytes(twoHyphens + boundary + lineEnd);
		dos.writeBytes("Content-Disposition: form-data; name=\"fileToUpload\";filename=\"" + sourceFileUri
			+ "\"" + lineEnd);

		dos.writeBytes(lineEnd);

		// create a buffer of maximum size
		bytesAvailable = fileInputStream.available();

		bufferSize = Math.min(bytesAvailable, maxBufferSize);
		buffer = new byte[bufferSize];

		// read file and write it into form...
		bytesRead = fileInputStream.read(buffer, 0, bufferSize);

		while (bytesRead > 0) {
		    dos.write(buffer, 0, bufferSize);
		    bytesAvailable = fileInputStream.available();
		    bufferSize = Math.min(bytesAvailable, maxBufferSize);
		    bytesRead = fileInputStream.read(buffer, 0, bufferSize);
		}

		// send multipart form data necesssary after file data...
		dos.writeBytes(lineEnd);
		dos.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);

		// Responses from the server (code and message)
		serverResponseCode = conn.getResponseCode();
		String serverResponseMessage = conn.getResponseMessage();

		Log.i("uploadFile", "HTTP Response is : " + serverResponseMessage + ": " + serverResponseCode);

		if (serverResponseCode == 200) {
		    boolean deleted = sourceFile.delete();
		    Log.i("Deleted succes", String.valueOf(deleted));
		}
		// close the streams //
		fileInputStream.close();
		dos.flush();
		dos.close();
	    } catch (MalformedURLException ex) {
		ex.printStackTrace();
		Log.e("Upload file to server", "error: " + ex.getMessage(), ex);
	    } catch (Exception e) {
		e.printStackTrace();
		Log.e("Upload file to server", "Exception : " + e.getMessage(), e);
	    }
	    return serverResponseCode;
	} // End else block
    }

    /**
     * @return true Enough. false Too little.
     * @brief Determines if there is enough storage space.
     */
    public boolean IsThereStorageSpace() {
	File extStorageDirFile = Environment.getExternalStorageDirectory();
	double totalSpaceInBytes = extStorageDirFile.getTotalSpace(),
		freeSpaceInBytes = extStorageDirFile.getFreeSpace(),
		percentFreeSpace = 100d * freeSpaceInBytes / totalSpaceInBytes;
	Log.d(TAG, String.format("%.2f%% remaining.", percentFreeSpace));
	return Math.abs(percentFreeSpace - miniFreeStorageSpacePercent) > tolerancePerOne;
    }

    /**
     * @return True/false.
     * @brief Checks if external storage is available for read and write.
     */
    public boolean isExternalStorageWritable() {
	String state = Environment.getExternalStorageState();
	return Environment.MEDIA_MOUNTED.equals(state);
    }

    @Override
    public void onError(MediaRecorder arg0, int errorType, int arg2) {
	String errorName = null;
	switch (errorType) {
	case MediaRecorder.MEDIA_ERROR_SERVER_DIED:
	    errorName = "MEDIA_ERROR_SERVER_DIED";
	    break;
	case MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED:
	    errorName = "MEDIA_RECORDER_INFO_MAX_DURATION_REACHED";
	    break;
	case MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED:
	    errorName = "MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED";
	    break;
	default:
	    errorName = "MEDIA_RECORDER_ERROR_UNKNOWN";
	    break;
	}
	Log.e(TAG, String.format("onError: (%d) %s", arg2, errorName));
    }
}
