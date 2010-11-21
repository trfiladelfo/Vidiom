package au.com.infiniterecursion.roboticeye;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.hardware.Camera;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Toast;


/*
 * Main RoboticEye Activity 
 * 
 * AUTHORS:
 * 
 * Andy Nicholson
 * 
 * 2010
 * Copyright Infinite Recursion Pty Ltd.
 * http://www.infiniterecursion.com.au
 */

public class MainActivity extends Activity implements SurfaceHolder.Callback, RoboticEyeActivity,
		MediaRecorder.OnInfoListener {

	private static final String TAG = "RoboticEye";

	//Menu ids
	private static final int MENU_ITEM_1 = Menu.FIRST;
	private static final int MENU_ITEM_2 = MENU_ITEM_1 + 1;
	private static final int MENU_ITEM_3 = MENU_ITEM_2 + 1;
	private static final int MENU_ITEM_4 = MENU_ITEM_3 + 1;
	private static final int MENU_ITEM_5 = MENU_ITEM_4 + 1;
	private static final int MENU_ITEM_6 = MENU_ITEM_5 + 1;
	private static final int MENU_ITEM_7 = MENU_ITEM_6 + 1;
	
	//Camera objects
	//
	private SurfaceView surfaceView;
	private SurfaceHolder surfaceHolder;
	private Camera camera;
	private boolean previewRunning;

	// Objects for recording
	private MediaRecorder mediaRecorder;
	// eg 40 seconds max video
	private int maxDurationInMs = 0;
	//eg 1MB limit
	private long maxFileSizeInBytes = 0;
	private final int videoFramesPerSecond = 25;

	//App state
	private boolean recordingInMotion;
	//Filenames (abs, relative) for latest recorded video file.
	private String latestVideoFile_absolutepath;
	private String latestVideoFile_filename;
	private boolean canSendVideoFile;
	private boolean uploadedSuccessfully;
	private long startTimeinMillis;
	private long endTimeinMillis;
	
	
	//Video files
	private File folder;
	private String rootSDcardFolder = "/RoboticEye/";
	private boolean canAccessSDCard = false;

	//Preferences
	private boolean autoEmailPreference;
	private boolean fTPPreference;
	private boolean videobinPreference;
	private String emailPreference;
	private String filenameConventionPrefence;
	private String maxDurationPreference;
	private String maxFilesizePreference;
	
	//Message queue
	private Handler handler;

	//Database
	private DBUtils db_utils;
	
	private PublishingUtils pu;

	private SharedPreferences prefs;
	
	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		pu=new PublishingUtils();
		
		Log.d(TAG,"On create");
		
		setContentView(R.layout.surface_layout);
		surfaceView = (SurfaceView) findViewById(R.id.surface_camera);

		surfaceHolder = surfaceView.getHolder();
		surfaceHolder.addCallback(this);
		surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

		recordingInMotion = false;
		canSendVideoFile = false;
		latestVideoFile_absolutepath = "";
		latestVideoFile_filename = "";
		uploadedSuccessfully = false;
		
		handler = new Handler();
		
		startTimeinMillis=endTimeinMillis=0;
		prefs = PreferenceManager
		.getDefaultSharedPreferences(getBaseContext());
		
		hideProgressIndicator();
		
		// check our folder exists, and if not make it
		checkInstallDirandCreateIfMissing();
		
		// Initial install?
		checkIfFirstTimeRunAndWelcome();
		
		db_utils = new DBUtils(getBaseContext());
	}
	
	
	@Override
	public void onResume() {
		super.onResume();
		Log.d(TAG,"On resume");
		loadPreferences();
		
		
	}

	@Override
	public void onPause() {
		
		super.onDestroy();
		Log.d(TAG,"On pause");
		if (mediaRecorder != null) {
			if (recordingInMotion) {
				stopRecording();
			}
			mediaRecorder.release();
		}
		
		
	}
	
	public void showProgressIndicator() {
		
		findViewById(R.id.uploadprogress).setVisibility(View.VISIBLE);
	}
	
	public void hideProgressIndicator() {
		
		// Hide the progress bar
		findViewById(R.id.uploadprogress)
				.setVisibility(View.INVISIBLE);
	}
	
	private void checkIfFirstTimeRunAndWelcome() {
		// 
		boolean first_time = prefs.getBoolean("firstTimeRun", true);
		
		if (first_time) {
			Editor editor = prefs.edit();
			editor.putBoolean("firstTimeRun", false);
			editor.commit();
			
			//Welcome dialog!
			new AlertDialog.Builder(MainActivity.this)
			.setMessage(R.string.welcome)
			.setPositiveButton(R.string.yes,
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog,
								int whichButton) {

						}
					}).show();
			
			
		}
		
	}
	
	private void loadPreferences() {
		
		autoEmailPreference = prefs.getBoolean("autoemailPreference", false);
		fTPPreference = prefs.getBoolean("ftpPreference", false);
		videobinPreference = prefs.getBoolean("videobinPreference", false);
		
		emailPreference = prefs.getString("emailPreference",null);
	
		// Filename style, duration, max filesize
		Resources res = getResources();

		filenameConventionPrefence = prefs.getString("filenameConventionPrefence",res.getString(R.string.filenameConventionDefaultPreference));
		maxDurationPreference = prefs.getString("maxDurationPreference",res.getString(R.string.maxDurationPreferenceDefault));
		maxFilesizePreference = prefs.getString("maxFilesizePreference",res.getString(R.string.maxFilesizePreferenceDefault));
				
		Log.d(TAG,"behaviour preferences are " + autoEmailPreference+":"+fTPPreference+":"+videobinPreference+":"+emailPreference);
		
		Log.d(TAG,"video recording preferences are " + filenameConventionPrefence+":"+maxDurationPreference+":"+maxFilesizePreference);
	}

	private void checkInstallDirandCreateIfMissing() {
		// android.os.Environment.getExternalStorageDirectory().getPath()
		folder = new File(Environment.getExternalStorageDirectory()
				+ rootSDcardFolder);
		boolean success;
		if (!folder.exists()) {

			Log.d(TAG, " Folder doesnt exit ... attempting to make it");

			success = folder.mkdir();
			if (!success) {
				canAccessSDCard = false;

				new AlertDialog.Builder(this)
						.setMessage(R.string.sdcard_failed)
						.setPositiveButton(R.string.yes,
								new DialogInterface.OnClickListener() {
									public void onClick(DialogInterface dialog,
											int whichButton) {

									}
								})

						.setNegativeButton(R.string.cancel,
								new DialogInterface.OnClickListener() {
									public void onClick(DialogInterface dialog,
											int whichButton) {

									}
								}).show();
			} else {
				Log.d(TAG, " Folder created...");
				canAccessSDCard = true;
			}
		} else {
			Log.d(TAG, " Folder already exists...");
			canAccessSDCard = true;
		}
	}

	/*
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		super.onCreateOptionsMenu(menu);
		Log.i(TAG, "OnCreateOptionsMenu called");

		// Conditionally on menu items.
		menu.add(0, MENU_ITEM_1, 0, R.string.menu_start_recording);

		addConstantMenuItems(menu);
		return true;
	}
	*/

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		super.onPrepareOptionsMenu(menu);

		Log.i(TAG, "OnPrepareOptionsMenu called");

		createConditionalMenu(menu);

		addConstantMenuItems(menu);

		return true;
	}

	private void createConditionalMenu(Menu menu) {
		menu.clear();

		Log.i(TAG, "createConditionalMenu called. recordingInMotion ? "  + recordingInMotion + " : canSendVideoFile ? " + canSendVideoFile);
		// Conditionally on menu items.
		if (recordingInMotion) {
			menu.removeItem(MENU_ITEM_1);
			MenuItem menu_stop = menu.add(0, MENU_ITEM_2, 0,R.string.menu_stop_recording);
			menu_stop.setIcon(R.drawable.stop48);
		} else {
			MenuItem menu_start = menu.add(0, MENU_ITEM_1, 0, R.string.menu_start_recording);
			menu_start.setIcon(R.drawable.sun48);
			menu.removeItem(MENU_ITEM_2);
		}

		if (canSendVideoFile) {
			MenuItem menu_publish = menu.add(0, MENU_ITEM_3, 0,R.string.menu_publish_to_videobin);
			menu_publish.setIcon(R.drawable.globe48);
			MenuItem menu_email = menu.add(0, MENU_ITEM_4, 0, R.string.menu_send_via_email);
			menu_email.setIcon(R.drawable.movie48);
			//XXX Add in publish to FTP host
			
			
		} else {
			menu.removeItem(MENU_ITEM_3);
			menu.removeItem(MENU_ITEM_4);
		}
	}
	

	private void addConstantMenuItems(Menu menu) {
		// ALWAYS ON menu items.
		MenuItem menu_about = menu.add(0, MENU_ITEM_5, 0, R.string.menu_about);
		menu_about.setIcon(R.drawable.wizard48);
		MenuItem menu_prefs = menu.add(0, MENU_ITEM_6, 0, R.string.menu_preferences);
		menu_prefs.setIcon(R.drawable.options);
		MenuItem menu_library = menu.add(0, MENU_ITEM_7, 0, R.string.menu_library);
		menu_library.setIcon(R.drawable.business48);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem menuitem) {
		int menuNum = menuitem.getItemId();

		Log.d("MENU", "Option " + menuNum + " selected");

		switch (menuitem.getItemId()) {

		// Record
		case MENU_ITEM_1:

			menuResponseForRecordItem();

			break;

		// Stop
		case MENU_ITEM_2:
			menuResponseForStopItem();
			break;

		// Post to Video FTP service
		case MENU_ITEM_3:

			menuResponseForPublishItem();

			break;

		case MENU_ITEM_4:
			// Email
			menuResponseForEmailItem();
			break;

		case MENU_ITEM_5:
			// ABOUT
			new AlertDialog.Builder(this)
					.setMessage(R.string.about_this)
					.setPositiveButton(R.string.yes,
							new DialogInterface.OnClickListener() {
								public void onClick(DialogInterface dialog,
										int whichButton) {

								}
							}).show();

			break;

		case MENU_ITEM_6:

			// Preferences
			Intent intent = new Intent().setClass(this,
					PreferencesActivity.class);
			this.startActivityForResult(intent, 0);

			break;

		case MENU_ITEM_7:
			
			//Library menu option
			
			// Launch library activity, showing list of recorded videos
			// their properties, if they are still 'on disk'
			// how they were published, links to published sites 
			// etc
			Intent intent2 = new Intent().setClass(this,
					LibraryActivity.class);
			this.startActivityForResult(intent2, 0);
			
			break;
			
			
		default:
			return super.onOptionsItemSelected(menuitem);
		}

		return true;
	}

	/*
	 * 
	 * Menu response methods
	 */

	private void menuResponseForEmailItem() {
		
		Log.d(TAG, "State is canSendVideoFile:"+canSendVideoFile + " recordingInMotion:"+recordingInMotion);
		
		if (canSendVideoFile && !recordingInMotion) {

			pu.launchEmailIntentWithCurrentVideo(this, latestVideoFile_absolutepath);

		} else if (recordingInMotion) {

			new AlertDialog.Builder(this)
					.setMessage(R.string.stop_recording_to_send)
					.setPositiveButton(R.string.yes,
							new DialogInterface.OnClickListener() {
								public void onClick(DialogInterface dialog,
										int whichButton) {

								}
							})

					.show();

		} else if (!recordingInMotion) {

			new AlertDialog.Builder(this)
					.setMessage(R.string.haventrecorded)
					.setPositiveButton(R.string.yes,
							new DialogInterface.OnClickListener() {
								public void onClick(DialogInterface dialog,
										int whichButton) {

								}
							})

					.show();

		}
	}

	private void menuResponseForPublishItem() {
		Log.d(TAG, "State is canSendVideoFile:"+canSendVideoFile + " recordingInMotion:"+recordingInMotion);
		
		if (canSendVideoFile && !recordingInMotion) {

			pu.doPOSTtoVideoBin(this, handler, latestVideoFile_absolutepath, emailPreference);

		} else if (recordingInMotion) {

			new AlertDialog.Builder(this)
					.setMessage(R.string.stop_recording_to_send)
					.setPositiveButton(R.string.yes,
							new DialogInterface.OnClickListener() {
								public void onClick(DialogInterface dialog,
										int whichButton) {

								}
							})

					.show();

		} else if (!recordingInMotion) {

			new AlertDialog.Builder(this)
					.setMessage(R.string.haventrecorded)
					.setPositiveButton(R.string.yes,
							new DialogInterface.OnClickListener() {
								public void onClick(DialogInterface dialog,
										int whichButton) {

								}
							})

					.show();

		}
	}

	private void menuResponseForStopItem() {
		Log.d(TAG, "State is canSendVideoFile:"+canSendVideoFile + " recordingInMotion:"+recordingInMotion);
		
		if (recordingInMotion) {

			stopRecording();

			
		} else {
			//
			new AlertDialog.Builder(this)
					.setMessage(R.string.notrecording)
					.setPositiveButton(R.string.yes,
							new DialogInterface.OnClickListener() {
								public void onClick(DialogInterface dialog,
										int whichButton) {

								}
							})

					.setNegativeButton(R.string.cancel,
							new DialogInterface.OnClickListener() {
								public void onClick(DialogInterface dialog,
										int whichButton) {

								}
							}).show();
		}
	}

	private void menuResponseForRecordItem() {
		Log.d(TAG, "State is canSendVideoFile:"+canSendVideoFile + " recordingInMotion:"+recordingInMotion);
		
		if (!uploadedSuccessfully && canSendVideoFile) {

			new AlertDialog.Builder(this)
					.setMessage(R.string.this_will_wipe_existing_video)
					.setPositiveButton(R.string.yes,
							new DialogInterface.OnClickListener() {
								public void onClick(DialogInterface dialog,
										int whichButton) {

									tryToStartRecording();

								}

							})

					.setNegativeButton(R.string.cancel,
							new DialogInterface.OnClickListener() {
								public void onClick(DialogInterface dialog,
										int whichButton) {

								}
							}).show();

		} else {

			tryToStartRecording();
		}
	}

	
	
	/*
	 * Camera methods
	 * 
	 *
	 */
	
	public void surfaceChanged(SurfaceHolder holder, int format, int width,
			int height) {
		//
		if (previewRunning) {
			camera.stopPreview();
		}
		Camera.Parameters p = camera.getParameters();

		Log.d(TAG, " format width height are : " + format + ":" + width + ":"
				+ height);

		// 320, 240 seems only possible resolution
		// and it seems the preview size must be the same as the video size
		//
		p.setPreviewSize(320, 240);
		// p.setPictureSize(320,240);
		p.setPreviewFormat(PixelFormat.YCbCr_420_SP);

		p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
		
		//Log.d(TAG, "Parameters are " + p.toString());

		camera.setParameters(p);

		try {
			camera.setPreviewDisplay(holder);
			camera.startPreview();
			previewRunning = true;
		} catch (IOException e) {
			Log.e(TAG, e.getMessage());
			e.printStackTrace();
		}
	}
	

	public void surfaceCreated(SurfaceHolder holder) {
		//
		Log.d(TAG,"surfaceCreated!");
		camera = Camera.open();
		if (camera != null) {
			Camera.Parameters params = camera.getParameters();
			
			camera.setParameters(params);
			
			
			
		} else {
			Toast.makeText(getApplicationContext(), "Camera not available!",
					Toast.LENGTH_LONG).show();
			finish();
		}
	}

	public void surfaceDestroyed(SurfaceHolder holder) {
		//
		Log.d(TAG,"surfaceDestroyed!");
		camera.stopPreview();
		previewRunning = false;
		camera.release();
	}
	

	private void tryToStartRecording() {
		if (canAccessSDCard && startRecording()) {

			recordingInMotion = true;

		} else {

			new AlertDialog.Builder(MainActivity.this)
					.setMessage(R.string.camera_failed)
					.setPositiveButton(R.string.yes,
							new DialogInterface.OnClickListener() {
								public void onClick(DialogInterface dialog,
										int whichButton) {

								}
							})

					.setNegativeButton(R.string.cancel,
							new DialogInterface.OnClickListener() {
								public void onClick(DialogInterface dialog,
										int whichButton) {

								}
							}).show();

			recordingInMotion = false;
		}
	}

	public boolean startRecording() {
		
			canSendVideoFile = false;

			camera.unlock();

			mediaRecorder = new MediaRecorder();
			mediaRecorder.setOnInfoListener(this);

			mediaRecorder.setCamera(camera);
			mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
			mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
			mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
			mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.DEFAULT);
			mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.DEFAULT);

			Log.d(TAG, " startRecording - preferences are " + maxDurationPreference + ":" + filenameConventionPrefence+":"+maxFilesizePreference);
			
			Integer user_duration = Integer.parseInt(maxDurationPreference);
			//preferences for user in seconds.
			maxDurationInMs = user_duration * 1000;
			mediaRecorder.setMaxDuration(maxDurationInMs);

			// Video file name selection process
			String new_videofile_name = "RoboticEye-";
			String file_ext_name = ".mp4";
			
			Resources res = getResources();
			if (filenameConventionPrefence.compareTo(res.getString(R.string.filenameConventionDefaultPreference)) == 0) {
				//The default is by date
				SimpleDateFormat postFormater = new SimpleDateFormat("yyyy-MM-dd-HH-mm"); 
				Calendar cal = Calendar.getInstance();
				Date now = cal.getTime();
				String newDateStr = postFormater.format(now); 
				
				new_videofile_name += newDateStr + file_ext_name;
				
				
					
			} else {
				//Sequentially 
				
				//look into database for this number
				int next_number = db_utils.getNextFilenameNumberAndIncrement();
				
				//XXX deal with -1 error condition
				
				new_videofile_name += next_number + file_ext_name;
				
			}
			//save JUST the latest filename 
			latestVideoFile_filename = new_videofile_name;
			
			File tempFile = new File(folder.getAbsolutePath(), new_videofile_name);
			mediaRecorder.setOutputFile(tempFile.getAbsolutePath());

			Log.d(TAG, "Starting recording into " + tempFile.getAbsolutePath());

			//Save the entire path to latest recorded video.
			latestVideoFile_absolutepath = tempFile.getAbsolutePath();
			
			
			mediaRecorder.setVideoFrameRate(videoFramesPerSecond);

			// mediaRecorder.setVideoSize(surfaceView.getWidth(),
			// surfaceView.getHeight())

			mediaRecorder.setVideoSize(320, 240);

			mediaRecorder.setPreviewDisplay(surfaceHolder.getSurface());

			Integer user_filesize = Integer.parseInt(maxFilesizePreference);
			//preferences for user in KB.
			maxFileSizeInBytes = user_filesize * 1024;
			mediaRecorder.setMaxFileSize(maxFileSizeInBytes);
	
		try {
			
			mediaRecorder.prepare();
			mediaRecorder.start();

			startTimeinMillis = System.currentTimeMillis();
			
			return true;
		} catch (IllegalStateException e) {
			Log.e(TAG, "Illegal State Exception" + e.getMessage());
			e.printStackTrace();
			return false;
		} catch (IOException e) {
			Log.e(TAG, e.getMessage());
			e.printStackTrace();
			return false;
		}
	}
	

	public void stopRecording() {

		mediaRecorder.stop();
		camera.lock();
		recordingInMotion = false;
		canSendVideoFile = true;
		
		doAutoCompletedRecordedActions();
		
		endTimeinMillis = System.currentTimeMillis();
		
		Log.d(TAG, "Recording time of video is " + ((endTimeinMillis-startTimeinMillis)/1000) + " seconds. filename " + latestVideoFile_filename + " : path " + latestVideoFile_absolutepath);
		
		db_utils.updateSDFileRecordwithNewVideoRecording(latestVideoFile_absolutepath, latestVideoFile_filename ,(int) ((endTimeinMillis-startTimeinMillis)/1000), "h263;samr");
	}
	

	public void onInfo(MediaRecorder mr, int what, int extra) {
		//
		if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
				|| what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
			Log.d(TAG, "We have reached the end limit");
			
			stopRecording();
		}

	}
	
	private void doAutoCompletedRecordedActions() {
		// Auto completion actions

		Log.d(TAG, "Doing auto completed recorded actions");
		
		if (videobinPreference) {
			pu.doPOSTtoVideoBin(this, handler, latestVideoFile_absolutepath, emailPreference);
		}

		if (fTPPreference) {
			pu.doVideoFTP(this, latestVideoFile_filename, latestVideoFile_absolutepath);
		}

		if (autoEmailPreference) {
			pu.launchEmailIntentWithCurrentVideo(this, latestVideoFile_absolutepath);
		}
	}

}