package com.zimmem.hostsswitcher;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLConnection;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Handler;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.Toast;

import com.stericson.RootTools.RootTools;
import com.stericson.RootTools.exceptions.RootDeniedException;
import com.stericson.RootTools.execution.CommandCapture;

public class SyncHostWidgetProvider extends AppWidgetProvider {
	private static final String HOSTS_FILE_NAME = "hosts";
	private static final String HOSTS_FILE_PATH = "/system/etc/"
			+ HOSTS_FILE_NAME;

	private static final String LINE_SEPARATOR = System.getProperty(
			"line.separator", "\n");
	private static final String MOUNT_TYPE_RO = "ro";
	private static final String MOUNT_TYPE_RW = "rw";
	private static final String COMMAND_RM = "rm -f";
	private static final String COMMAND_CHOWN = "chown 0:0";
	private static final String COMMAND_CHMOD_644 = "chmod 644";

	@Override
	public void onUpdate(Context context, AppWidgetManager appWidgetManager,
			int[] appWidgetIds) {
		super.onUpdate(context, appWidgetManager, appWidgetIds);
		Intent intent = new Intent(
				"com.zimmem.hostsswitcher.action.widget.click");
		RemoteViews remoteViews = new RemoteViews(context.getPackageName(),
				R.layout.sync_button_widget);
		PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0,
				intent, 0);
		remoteViews.setOnClickPendingIntent(R.id.widget_sync_layout,
				pendingIntent);
		remoteViews.setOnClickPendingIntent(R.id.sync_button, pendingIntent);
		appWidgetManager.updateAppWidget(appWidgetIds, remoteViews);

	}

	@Override
	public void onReceive(Context context, Intent intent) {
		super.onReceive(context, intent);
		Log.e("", intent.toString());

		if (intent.getAction().equals("com.zimmem.hostsswitcher.action.sync.tip")) {
			Toast.makeText(context, intent.getStringExtra("message"),
					Toast.LENGTH_LONG).show();
		} else if (intent.getAction().equals(
				"com.zimmem.hostsswitcher.action.widget.click")) {
			onClick(context, intent);
		}

	}

	private void onClick(final Context context, Intent intent) {
		if (!RootTools.isAccessGiven()) {
			showMessage(context, "Can't get root access");
			return;
		}

		String path = HOSTS_FILE_PATH;
		File hostsFile = new File(HOSTS_FILE_PATH);
		if (hostsFile.exists()) {
			try {
				if (isSymlink(hostsFile)) {
					path = hostsFile.getCanonicalPath();
				}
			} catch (IOException e1) {
				showMessage(context, e1.toString());
				return;
			}
		} else {
			showMessage(context, "Hosts file was not found in filesystem");
			return;
		}
		final String hostsFilePath = path;

		AsyncTask<String, Void, Void> executer = (new AsyncTask<String, Void, Void>() {

			@Override
			protected Void doInBackground(String... params) {
				String tmpFile = String.format(Locale.US, "%s/%s", context
						.getFilesDir().getAbsolutePath(), HOSTS_FILE_NAME);
				String content = null;
				try {
					content = getRemoteFile("https://raw.githubusercontent.com/zimmem/hosts/master/android/hosts");
				} catch (IOException e1) {
					showMessage(context, e1.toString(), true);
					return null;
				}

				File temp = new File(tmpFile);
				FileWriter write = null;
				try {
					write = new FileWriter(temp);
					write.write(content);
				} catch (IOException e1) {
					showMessage(context, e1.toString(), true);
					return null;
				} finally {
					try {
						write.close();
					} catch (IOException e) {
						showMessage(context, e.toString(), true);
						return null;
					}
				}

				// Step 3: Create backup of current hosts file (if any)
				RootTools.remount(hostsFilePath, MOUNT_TYPE_RW);

				// Step 4: Replace hosts file with generated file
				try {
					runRootCommand(COMMAND_RM, hostsFilePath);
					Log.d("file", tmpFile);
					Log.d("file", hostsFilePath);
					RootTools.copyFile(tmpFile, hostsFilePath, false, true);

					// Step 5: Give proper rights
					runRootCommand(COMMAND_CHOWN, hostsFilePath);
					runRootCommand(COMMAND_CHMOD_644, hostsFilePath);
					showMessage(context, "Switch Success", true);
				} catch (Exception e) {
					showMessage(context, e.toString(), true);
				}
				return null;

			}
		});
		executer.execute();

	}

	private void showMessage(final Context context, String message) {
		showMessage(context, message, false);
	}

	private void showMessage(final Context context, String message, boolean asyn) {
		if (asyn) {
			Intent intent = new Intent("com.zimmem.hostsswitcher.action.sync.tip");
			intent.putExtra("message", message);
			context.sendBroadcast(intent);
		} else {
			Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
		}

	}

	public static boolean isSymlink(File file) throws IOException {
		if (file == null)
			throw new NullPointerException("File must not be null");
		File canon;
		if (file.getParent() == null) {
			canon = file;
		} else {
			File canonDir = file.getParentFile().getCanonicalFile();
			canon = new File(canonDir, file.getName());
		}
		return !canon.getCanonicalFile().equals(canon.getAbsoluteFile());
	}

	/**
	 * Executes a single argument root command.
	 * <p>
	 * <b>Must be in an async call.</b>
	 * </p>
	 *
	 * @param command
	 *            a command, ie {@code "rm -f"}, {@code "chmod 644"}...
	 * @param uniqueArg
	 *            the unique argument for the command, usually the file name
	 */
	private void runRootCommand(String command, String uniqueArg)
			throws IOException, TimeoutException, RootDeniedException  {
		CommandCapture cmd = new CommandCapture(0, false, String.format(
				Locale.US, "%s %s", command, uniqueArg));
		RootTools.getShell(true).add(cmd);
	}

	private String getRemoteFile(String uri) throws IOException {
		URL url = new URL(uri);
		URLConnection connection = url.openConnection();
		InputStream is = connection.getInputStream();
		BufferedReader reader = new BufferedReader(new InputStreamReader(is));
		StringWriter write = new StringWriter();
		String line = null;
		while ((line = reader.readLine()) != null) {
			write.write(line);
			write.write(LINE_SEPARATOR);
		}
		Log.d("net", "hosts : " + write.toString());
		return write.toString();

	}
}
