package org.witness.proofmode.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.safetynet.SafetyNetApi;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;

import org.witness.proofmode.ProofMode;
import org.witness.proofmode.crypto.HashUtils;
import org.witness.proofmode.crypto.PgpUtils;
import org.witness.proofmode.notarization.GoogleSafetyNetNotarizationProvider;
import org.witness.proofmode.notarization.NotarizationListener;
import org.witness.proofmode.notarization.NotarizationProvider;
import org.witness.proofmode.notarization.OpenTimestampsNotarizationProvider;
import org.witness.proofmode.util.DeviceInfo;
import org.witness.proofmode.util.GPSTracker;
import org.witness.proofmode.util.RecursiveFileObserver;
import org.witness.proofmode.util.SafetyNetCheck;
import org.witness.proofmode.util.SafetyNetResponse;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import timber.log.Timber;

import static org.witness.proofmode.ProofMode.GOOGLE_SAFETYNET_FILE_TAG;
import static org.witness.proofmode.ProofMode.OPENPGP_FILE_TAG;
import static org.witness.proofmode.ProofMode.OPENTIMESTAMPS_FILE_TAG;
import static org.witness.proofmode.ProofMode.PREFS_DOPROOF;
import static org.witness.proofmode.ProofMode.PROOF_FILE_TAG;

public class MediaWatcher extends BroadcastReceiver {

    private final static String PROOF_BASE_FOLDER = "proofmode/";

    private static boolean mStorageMounted = false;
    private SharedPreferences mPrefs;

    public final static int PROOF_GENERATION_DELAY_TIME_MS = 30 * 1000; // 30 seconds
    private static MediaWatcher mInstance;

    private ExecutorService mExec = Executors.newFixedThreadPool(1);

    private Context mContext = null;

    private MediaWatcher (Context context) {
        if (mPrefs == null)
            mPrefs = PreferenceManager.getDefaultSharedPreferences(context);

        mContext = context;

        startFileSystemMonitor();
    }

    public static synchronized MediaWatcher getInstance (Context context)
    {
        if (mInstance == null)
            mInstance = new MediaWatcher(context);

        return mInstance;
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {

        mExec.submit(() -> {

            boolean doProof = mPrefs.getBoolean(PREFS_DOPROOF, true);

            if (doProof)
                handleIntent(context, intent);
        });


    }


    public String processUri (Uri fileUri) {
        try {
            Intent intent = new Intent();
            intent.setData(fileUri);
            return handleIntent(mContext, intent);
        }
        catch (RuntimeException re)
        {
            Timber.e(re,"RUNTIME EXCEPTION processing media file: " + re);
            return null;
        }
        catch (Error err)
        {
            Timber.e(err,"FATAL ERROR processing media file: " + err);

            return null;
        }
    }

    public String processUri (Uri fileUri, String proofHash) {
        try {
            Intent intent = new Intent();
            intent.setData(fileUri);
            intent.putExtra("hash",proofHash);
            return handleIntent(mContext, intent);
        }
        catch (RuntimeException re)
        {
            Timber.e(re,"RUNTIME EXCEPTION processing media file: " + re);
            return null;
        }
        catch (Error err)
        {
            Timber.e(err,"FATAL ERROR processing media file: " + err);

            return null;
        }
    }

    public String handleIntent (final Context context, Intent intent) {

        if (mPrefs == null)
            mPrefs = PreferenceManager.getDefaultSharedPreferences(context);


        if (intent.getAction() != null) {
            if (intent.getAction().equals(Intent.ACTION_UMS_CONNECTED)) {
                mStorageMounted = true;
            } else if (intent.getAction().equals(Intent.ACTION_UMS_DISCONNECTED)) {
                mStorageMounted = false;
            }
        }

        Uri tmpUriMedia = intent.getData();
        if (tmpUriMedia == null)
            tmpUriMedia = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);

        if (tmpUriMedia == null) //still null?
            return null;

        final Uri uriMedia = tmpUriMedia;

        final boolean showDeviceIds = mPrefs.getBoolean(ProofMode.PREF_OPTION_PHONE,ProofMode.PREF_OPTION_PHONE_DEFAULT);
        final boolean showLocation = mPrefs.getBoolean(ProofMode.PREF_OPTION_LOCATION,ProofMode.PREF_OPTION_LOCATION_DEFAULT);
        final boolean autoNotarize = mPrefs.getBoolean(ProofMode.PREF_OPTION_NOTARY, ProofMode.PREF_OPTION_NOTARY_DEFAULT);
        final boolean showMobileNetwork = mPrefs.getBoolean(ProofMode.PREF_OPTION_NETWORK,ProofMode.PREF_OPTION_NETWORK_DEFAULT);

        final String mediaHash;

        if (intent.hasExtra("hash"))
        {
            mediaHash = intent.getStringExtra("hash");
        }
        else {
            try {
                mediaHash = HashUtils.getSHA256FromFileContent(context.getContentResolver().openInputStream(uriMedia));
            } catch (FileNotFoundException e) {
                Timber.d( "FileNotFoundException: unable to open inputstream for hashing: %s", uriMedia);
                return null;
            } catch (IllegalStateException ise) {
                Timber.d( "IllegalStateException: unable to open inputstream for hashing: %s", uriMedia);
                return null;
            } catch (SecurityException e) {
                Timber.d( "SecurityException: security exception accessing URI: %s", uriMedia);
                return null;
            }
        }

        if (mediaHash != null) {

            try {
                if (proofExists(context,uriMedia,mediaHash))
                    return mediaHash;
            } catch (FileNotFoundException e) {
                //must not exist!
            }

            Timber.d("Writing proof for hash %s for path %s",mediaHash, uriMedia);

            String notes = "";

            try {
                PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                String version = pInfo.versionName;
                notes = "ProofMode v" + version;
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }

            //write immediate proof, w/o safety check result
            writeProof(context, uriMedia, mediaHash, showDeviceIds, showLocation, showMobileNetwork, null, false, false, -1, null, "none", notes);

            if (autoNotarize) {

                if (isOnline(context)) {

                    final GoogleSafetyNetNotarizationProvider gProvider = new GoogleSafetyNetNotarizationProvider(context);

                    try
                    {
                        gProvider.notarize(mediaHash, context.getContentResolver().openInputStream(uriMedia), new NotarizationListener() {
                            @Override
                            public void notarizationSuccessful(String result) {

                                SafetyNetResponse resp = gProvider.parseJsonWebSignature(result);

                                String apkDigest = resp.getApkPackageName() + "=" + resp.getApkDigestSha256();
                                long timestamp = resp.getTimestampMs();
                                boolean isBasicIntegrity = resp.isBasicIntegrity();
                                boolean isCtsMatch = resp.isCtsProfileMatch();

                                writeProof(context, uriMedia, mediaHash, showDeviceIds, showLocation, showMobileNetwork,
                                        apkDigest, isBasicIntegrity, isCtsMatch, timestamp, result, GOOGLE_SAFETYNET_FILE_TAG, GOOGLE_SAFETYNET_FILE_TAG);

                            }

                            @Override
                            public void notarizationFailed(int errCode, String message) {
                                Timber.d("Got Google SafetyNet error response: %s", message);

                            }
                        });


                        final NotarizationProvider nProvider = new OpenTimestampsNotarizationProvider();
                        nProvider.notarize(mediaHash, context.getContentResolver().openInputStream(uriMedia), new NotarizationListener() {
                            @Override
                            public void notarizationSuccessful(String resultData) {


                                Timber.d("Got OpenTimestamps success response timestamp: %s", resultData);
                                writeProof(context, uriMedia, mediaHash, showDeviceIds, showLocation, showMobileNetwork,
                                        null, false, false, new Date().getTime(), resultData, OPENTIMESTAMPS_FILE_TAG,OPENTIMESTAMPS_FILE_TAG);



                            }

                            @Override
                            public void notarizationFailed(int errCode, String message) {

                                Timber.d("Got OpenTimestamps error response: %s", message);
                         //       writeProof(context, uriMedia, mediaHash, showDeviceIds, showLocation, showMobileNetwork, null, false, false, -1, "Opentimestamps.org error: " + message);

                            }
                        });
                    } catch (FileNotFoundException e) {
                        Timber.e(e);
                    }
                }

            }

            return mediaHash;
        }
        else
        {
            Timber.d("Unable to access media files, no proof generated");

        }

        return null;
    }

    private boolean proofExists (Context context, Uri mediaUri, String hash) throws FileNotFoundException {
        boolean result = false;

        if (hash != null) {


            File fileFolder = MediaWatcher.getHashStorageDir(context,hash);

            if (fileFolder != null ) {
                File fileMediaProof = new File(fileFolder, hash + PROOF_FILE_TAG);


                if (fileMediaProof.exists()) {
                    Timber.d("Proof EXISTS for URI %s and hash %s", mediaUri, hash);

                    result = true;
                } else {
                    //generate now?
                    result = false;
                    Timber.d("Proof DOES NOT EXIST for URI %s and hash %s", mediaUri, hash);


                }
            }
        }

        return result;
    }


    public boolean isOnline(Context context) {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        return netInfo != null && netInfo.isConnected();
    }


    private void writeProof (Context context, Uri uriMedia, String hash, boolean showDeviceIds, boolean showLocation, boolean showMobileNetwork, String safetyCheckResult, boolean isBasicIntegrity, boolean isCtsMatch, long notarizeTimestamp, String notarizeData, String notarizeType, String notes)
    {

        boolean usePgpArmor = true;

        File fileFolder = getHashStorageDir(context,hash);

        if (fileFolder != null) {

            File fileMediaSig = new File(fileFolder, hash + OPENPGP_FILE_TAG);
            File fileMediaProof = new File(fileFolder, hash + PROOF_FILE_TAG);
            File fileMediaProofSig = new File(fileFolder, hash + PROOF_FILE_TAG + OPENPGP_FILE_TAG);
            File fileMediaNotarizeData = new File(fileFolder, hash + notarizeType);

            try {

                //add data to proof csv and sign again
                boolean writeHeaders = !fileMediaProof.exists();
                String buildProof = buildProof(context, uriMedia, writeHeaders, showDeviceIds, showLocation, showMobileNetwork, safetyCheckResult, isBasicIntegrity, isCtsMatch, notarizeTimestamp, notes);
                writeTextToFile(context, fileMediaProof, buildProof);

                if (fileMediaProof.exists()) {
                    //sign the proof file again
                    PgpUtils.getInstance(context).createDetachedSignature(fileMediaProof, fileMediaProofSig, PgpUtils.DEFAULT_PASSWORD, usePgpArmor);
                }

                //sign the media file
               if (!fileMediaSig.exists())
                  PgpUtils.getInstance(context).createDetachedSignature(context.getContentResolver().openInputStream(uriMedia), new FileOutputStream(fileMediaSig), PgpUtils.DEFAULT_PASSWORD, usePgpArmor);

                Timber.d("Proof written/updated for uri %s and hash %s", uriMedia, hash);

                try {
                    //try to save opentimestamps data to raw file
                    if (notarizeData != null) {
                        if (notarizeType.equals(OPENTIMESTAMPS_FILE_TAG)) {
                            byte[] rawNotarizeData = Base64.decode(notarizeData, Base64.DEFAULT);
                            writeBytesToFile(context, fileMediaNotarizeData, rawNotarizeData);
                        }
                        else
                        {
                            writeBytesToFile(context,fileMediaNotarizeData, notarizeData.getBytes("UTF-8"));
                        }
                    }
                }
                catch (Exception e)
                {
                    Timber.d("unable to save notarization data to file: " + e);
                }

            } catch (Exception e) {
                Timber.d( "Error signing media or proof: %s", e.getLocalizedMessage());
            }
        }
    }

    public static File getHashStorageDir(Context context, String hash) {

        // Get the directory for the user's public pictures directory.
        File fileParentDir = new File(context.getFilesDir(),PROOF_BASE_FOLDER);
        if (!fileParentDir.exists()) {
            fileParentDir.mkdir();
        }

        /**
        if (android.os.Build.VERSION.SDK_INT >= 19) {
            fileParentDir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS), PROOF_BASE_FOLDER);

        }
        else
        {
            fileParentDir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), PROOF_BASE_FOLDER);
        }

        if (!fileParentDir.exists()) {
            if (!fileParentDir.mkdir())
            {
                fileParentDir = new File(Environment.getExternalStorageDirectory(), PROOF_BASE_FOLDER);
                if (!fileParentDir.exists())
                    if (!fileParentDir.mkdir())
                        return null;
            }
        }**/

        File fileHashDir = new File(fileParentDir, hash + '/');
        if (!fileHashDir.exists())
            if (!fileHashDir.mkdir())
                return null;

        return fileHashDir;
    }

    /* Checks if external storage is available for read and write */
    public boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            return true;
        }
        return false;
    }

    private String buildProof (Context context, Uri uriMedia, boolean writeHeaders, boolean showDeviceIds, boolean showLocation, boolean showMobileNetwork, String safetyCheckResult, boolean isBasicIntegrity, boolean isCtsMatch, long notarizeTimestamp, String notes)
    {
        String mediaPath = null;

        if (uriMedia.getScheme() == null || uriMedia.getScheme().equalsIgnoreCase("file"))
        {
            mediaPath = uriMedia.getPath();
        }
        else {
            String[] projection = {MediaStore.Images.Media.DATA};

            Cursor cursor = context.getContentResolver().query(uriMedia, projection, null, null, null);

            if (cursor != null) {
                if (cursor.getCount() > 0) {

                    cursor.moveToFirst();
                    int colIdx = cursor.getColumnIndex(projection[0]);
                    if (colIdx > -1)
                        mediaPath = cursor.getString(colIdx);
                }

                cursor.close();
            }
        }

        String hash = null;
        try {
            hash = getSHA256FromFileContent(context.getContentResolver().openInputStream(uriMedia));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.FULL,DateFormat.FULL);

        HashMap<String, String> hmProof = new HashMap<>();

        if (mediaPath != null)
            hmProof.put("File Path",mediaPath);
        else
            hmProof.put("File Path",uriMedia.toString());

        hmProof.put("File Hash SHA256",hash);

        if (mediaPath != null)
            hmProof.put("File Modified",df.format(new Date(new File(mediaPath).lastModified())));

        hmProof.put("Proof Generated",df.format(new Date()));

        if (showDeviceIds) {
            hmProof.put("DeviceID", DeviceInfo.getDeviceId(context));
            hmProof.put("Wifi MAC", DeviceInfo.getWifiMacAddr());
        }

        hmProof.put("IPv4",DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_IP_ADDRESS_IPV4));
        hmProof.put("IPv6",DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_IP_ADDRESS_IPV6));

        hmProof.put("DataType",DeviceInfo.getDataType(context));
        hmProof.put("Network",DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_NETWORK));

        hmProof.put("NetworkType",DeviceInfo.getNetworkType(context));
        hmProof.put("Hardware",DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_HARDWARE_MODEL));
        hmProof.put("Manufacturer",DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_MANUFACTURE));
        hmProof.put("ScreenSize",DeviceInfo.getDeviceInch(context));

        hmProof.put("Language",DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_LANGUAGE));
        hmProof.put("Locale",DeviceInfo.getDeviceInfo(context, DeviceInfo.Device.DEVICE_LOCALE));



        if (showLocation)
        {
            GPSTracker gpsTracker = new GPSTracker(context);

            if (gpsTracker.canGetLocation()) {

                Location loc = gpsTracker.getLocation();

                int waitIdx = 0;
                while (loc == null && waitIdx < 3) {
                    waitIdx++;
                    try {
                        Thread.sleep(500);
                    } catch (Exception e) {
                    }
                    loc = gpsTracker.getLocation();
                }

                if (loc != null) {
                    hmProof.put("Location.Latitude", loc.getLatitude() + "");
                    hmProof.put("Location.Longitude", loc.getLongitude() + "");
                    hmProof.put("Location.Provider", loc.getProvider());
                    hmProof.put("Location.Accuracy", loc.getAccuracy() + "");
                    hmProof.put("Location.Altitude", loc.getAltitude() + "");
                    hmProof.put("Location.Bearing", loc.getBearing() + "");
                    hmProof.put("Location.Speed", loc.getSpeed() + "");
                    hmProof.put("Location.Time", loc.getTime() + "");
                }
                else
                {
                    hmProof.put("Location.Latitude", "");
                    hmProof.put("Location.Longitude", "");
                    hmProof.put("Location.Provider", "none");
                    hmProof.put("Location.Accuracy", "");
                    hmProof.put("Location.Altitude", "");
                    hmProof.put("Location.Bearing", "");
                    hmProof.put("Location.Speed", "");
                    hmProof.put("Location.Time", "");
                }

            }

            if (showMobileNetwork)
                hmProof.put("CellInfo", DeviceInfo.getCellInfo(context));
            else
                hmProof.put("CellInfo", "none");

        }
        else
        {
            hmProof.put("Location.Latitude", "");
            hmProof.put("Location.Longitude", "");
            hmProof.put("Location.Provider", "none");
            hmProof.put("Location.Accuracy", "");
            hmProof.put("Location.Altitude", "");
            hmProof.put("Location.Bearing", "");
            hmProof.put("Location.Speed", "");
            hmProof.put("Location.Time", "");
        }



        if (!TextUtils.isEmpty(safetyCheckResult)) {
            hmProof.put("SafetyCheck", safetyCheckResult);
            hmProof.put("SafetyCheckBasicIntegrity", isBasicIntegrity+"");
            hmProof.put("SafetyCheckCtsMatch", isCtsMatch+"");
            hmProof.put("SafetyCheckTimestamp", df.format(new Date(notarizeTimestamp)));
        }
        else
        {
            hmProof.put("SafetyCheck", "");
            hmProof.put("SafetyCheckBasicIntegrity", "");
            hmProof.put("SafetyCheckCtsMatch", "");
            hmProof.put("SafetyCheckTimestamp", "");
        }

        if (!TextUtils.isEmpty(notes))
            hmProof.put("Notes",notes);
        else
            hmProof.put("Notes","");


        StringBuffer sb = new StringBuffer();

        if (writeHeaders) {
            for (String key : hmProof.keySet()) {
                sb.append(key).append(",");
            }

            sb.append("\n");
        }

        for (String key : hmProof.keySet())
        {
            String value = hmProof.get(key);
            value = value.replace(',',' '); //remove commas from CSV file
            sb.append(value).append(",");
        }

        return sb.toString();

    }

    private static void writeBytesToFile (Context context, File fileOut, byte[] data)
    {
        try {
            DataOutputStream os = new DataOutputStream(new FileOutputStream(fileOut,true));
            os.write(data);
            os.flush();
            os.close();
        }
        catch (IOException ioe)
        {
            ioe.printStackTrace();
        }

    }

    private static void writeTextToFile (Context context, File fileOut, String text)
    {
        try {
            PrintStream ps = new PrintStream(new FileOutputStream(fileOut,true));
            ps.println(text);
            ps.flush();
            ps.close();
        }
        catch (IOException ioe)
        {
            ioe.printStackTrace();
        }

    }

    private static String getSHA256FromFileContent(String filename)
    {

        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536]; //created at start.
            InputStream fis = new FileInputStream(filename);
            int n = 0;
            while (n != -1)
            {
                n = fis.read(buffer);
                if (n > 0)
                {
                    digest.update(buffer, 0, n);
                }
            }
            byte[] digestResult = digest.digest();
            return asHex(digestResult);
        }
        catch (Exception e)
        {
            return null;
        }
    }


    private static String getSHA256FromFileContent(InputStream fis)
    {

        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536]; //created at start.
            int n = 0;
            while (n != -1)
            {
                n = fis.read(buffer);
                if (n > 0)
                {
                    digest.update(buffer, 0, n);
                }
            }
            byte[] digestResult = digest.digest();
            return asHex(digestResult);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static String asHex(byte[] arrayBytes) {
        StringBuffer stringBuffer = new StringBuffer();
        for (int i = 0; i < arrayBytes.length; i++) {
            stringBuffer.append(Integer.toString((arrayBytes[i] & 0xff) + 0x100, 16)
                    .substring(1));
        }
        return stringBuffer.toString();
    }

    private static final int READ_STORAGE_PERMISSION_REQUEST_CODE = 41;
    public boolean checkPermissionForReadExtertalStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int result = mContext.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE);
            return result == PackageManager.PERMISSION_GRANTED;
        }
        return false;
    }

    public static FileObserver observerMedia;

    private void startFileSystemMonitor() {

        if (checkPermissionForReadExtertalStorage()) {

            String pathToWatch = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getAbsolutePath();

            observerMedia = new RecursiveFileObserver(pathToWatch, FileObserver.CLOSE_WRITE|FileObserver.MOVED_TO) { // set up a file observer to watch this directory on sd card
                @Override
                public void onEvent(int event, final String mediaPath) {
                    if (mediaPath != null && (!mediaPath.equals(".probe"))) { // check that it's not equal to .probe because thats created every time camera is launched

                        Timer t = new Timer();
                        t.schedule(new TimerTask() {
                            @Override
                            public void run() {
                                File fileMedia = new File(mediaPath);
                                if (fileMedia.exists())
                                    processUri(Uri.fromFile(fileMedia));

                            }
                        }, MediaWatcher.PROOF_GENERATION_DELAY_TIME_MS);

                    }
                }
            };
            observerMedia.startWatching();

        }


    }

    public void stop () {

        if (observerMedia != null)
        {
            observerMedia.stopWatching();
        }
    }
}
