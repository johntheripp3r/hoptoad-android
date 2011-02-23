/*
    Hoptoad Notifier for Android
    Copyright (c) 2011 James Smith <james@loopj.com>
    http://loopj.com

    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in
    all copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
    THE SOFTWARE.
*/

package com.loopj.android.hoptoad;

import java.net.HttpURLConnection;
import java.net.URL;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.Random;

import org.xmlpull.v1.XmlSerializer;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.text.TextUtils;
import android.util.Log;
import android.util.Xml;


public class HoptoadNotifier {
    private static final String LOG_TAG = "HoptoadNotifier";

    // Basic settings
    private static final String HOPTOAD_ENDPOINT = "http://hoptoadapp.com/notifier_api/v2/notices";
    private static final String HOPTOAD_API_VERSION = "2.0";
    private static final String NOTIFIER_NAME = "Android Hoptoad Notifier";
    private static final String NOTIFIER_VERSION = "1.0.0";
    private static final String NOTIFIER_URL = "http://loopj.com";
    private static final String UNSENT_EXCEPTION_PATH = "/unsent_hoptoad_exceptions/";

    // Exception meta-data
    private static String environmentName = "default";
    private static String packageName = "unknown";
    private static String versionName = "unknown";
    private static String phoneModel = android.os.Build.MODEL;
    private static String androidVersion = android.os.Build.VERSION.RELEASE;

    // Hoptoad api key
    private static String apiKey;

    // Exception storage info
    private static String filePath;
    private static boolean diskStorageEnabled = false;

    // Wrapper class to send uncaught exceptions to hoptoad
    private static class HoptoadExceptionHandler implements UncaughtExceptionHandler {
        private UncaughtExceptionHandler defaultExceptionHandler;

        public HoptoadExceptionHandler(UncaughtExceptionHandler defaultExceptionHandlerIn) {
            defaultExceptionHandler = defaultExceptionHandlerIn;
        }

        public void uncaughtException(Thread t, Throwable e) {
            HoptoadNotifier.notify(e);
            defaultExceptionHandler.uncaughtException(t, e);
        }
    }

    // Register to send exceptions to hoptoad
    public static void register(Context context, String apiKey) {
        register(context, apiKey, null);
    }

    // Register to send exceptions to hoptoad (with an environment name)
    public static void register(Context context, String apiKey, String environmentName) {
        // Require a hoptoad api key
        if(apiKey != null) {
            HoptoadNotifier.apiKey = apiKey;
        } else {
            throw new RuntimeException("HoptoadNotifier requires a Hoptoad API key.");
        }

        // Fill in environment name if passed
        if(environmentName != null) {
            HoptoadNotifier.environmentName = environmentName;
        }

        // Connect our default exception handler
        UncaughtExceptionHandler currentHandler = Thread.getDefaultUncaughtExceptionHandler();
        if(!(currentHandler instanceof HoptoadExceptionHandler)) {
            Thread.setDefaultUncaughtExceptionHandler(new HoptoadExceptionHandler(currentHandler));
        }

        // Load up current package name and version
        try {
            packageName = context.getPackageName();
            PackageInfo pi = context.getPackageManager().getPackageInfo(packageName, 0);
            if(pi.versionName != null) {
                versionName = pi.versionName;
            }
        } catch (Exception e) {}

        // Prepare the file storage location
        filePath = context.getFilesDir().getAbsolutePath() + UNSENT_EXCEPTION_PATH;
        File outFile = new File(filePath);
        outFile.mkdirs();
        diskStorageEnabled = outFile.exists();

        Log.d(LOG_TAG, "Registered and ready to handle exceptions.");

        // Flush any existing exception info
        flushExceptions();
    }

    // Fire an exception to hoptoad manually
    public static void notify(Throwable e) {
        if(e != null && diskStorageEnabled) {
            writeExceptionToDisk(e);
            flushExceptions();
        }
    }

    private static void writeExceptionToDisk(Throwable e) {
        try {
            // Set up the output stream
            int random = new Random().nextInt(99999);
            String filename = filePath + versionName + "-" + String.valueOf(random) + ".xml";
            BufferedWriter writer = new BufferedWriter(new FileWriter(filename));
            XmlSerializer s = Xml.newSerializer();
            s.setOutput(writer);

            // Start ridiculous xml building
            s.startDocument("UTF-8", true);

            // Top level tag
            s.startTag("", "notice");
            s.attribute("", "version", HOPTOAD_API_VERSION);

            // Fill in the api key
            s.startTag("", "api-key");
            s.text(apiKey);
            s.endTag("", "api-key");

            // Fill in the notifier data
            s.startTag("", "notifier");
            s.startTag("", "name");
            s.text(NOTIFIER_NAME);
            s.endTag("", "name");
            s.startTag("", "version");
            s.text(NOTIFIER_VERSION);
            s.endTag("", "version");
            s.startTag("", "url");
            s.text(NOTIFIER_URL);
            s.endTag("", "url");
            s.endTag("", "notifier");

            // Fill in the error info
            s.startTag("", "error");
            s.startTag("", "class");
            s.text(e.getClass().getName());
            s.endTag("", "class");
            s.startTag("", "message");
            s.text("[" + versionName + "] " + e.toString());
            s.endTag("", "message");

            // Extract the stack traces
            s.startTag("", "backtrace");
            Throwable currentEx = e;
            while(currentEx != null) {
                StackTraceElement[] stackTrace = currentEx.getStackTrace();
                for(StackTraceElement el : stackTrace) {
                    s.startTag("", "line");
                    s.attribute("", "method", el.getClassName() + "." + el.getMethodName());
                    s.attribute("", "file", el.getFileName());
                    s.attribute("", "number", String.valueOf(el.getLineNumber()));
                    s.endTag("", "line");
                }

                currentEx = currentEx.getCause();
                if(currentEx != null) {
                    s.startTag("", "line");
                    s.attribute("", "file", "### CAUSED BY ###: " + currentEx.toString());
                    s.attribute("", "number", "");
                    s.endTag("", "line");
                }
            }
            s.endTag("", "backtrace");
            s.endTag("", "error");

            // Additional request info
            s.startTag("", "request");
            s.startTag("", "url");
            s.endTag("", "url");
            s.startTag("", "component");
            s.endTag("", "component");
            s.startTag("", "action");
            s.endTag("", "action");
            s.startTag("", "cgi-data");
            s.startTag("", "var");
            s.attribute("", "key", "Device");
            s.text(phoneModel);
            s.endTag("", "var");
            s.startTag("", "var");
            s.attribute("", "key", "Android Version");
            s.text(androidVersion);
            s.endTag("", "var");
            s.startTag("", "var");
            s.attribute("", "key", "App Version");
            s.text(versionName);
            s.endTag("", "var");
            s.endTag("", "cgi-data");
            s.endTag("", "request");

            // Production/development mode flag
            s.startTag("", "server-environment");
            s.startTag("", "environment-name");
            s.text(environmentName);
            s.endTag("", "environment-name");
            s.endTag("", "server-environment");

            // Close document
            s.endTag("", "notice");
            s.endDocument();

            // Flush to disk
            writer.flush();
            writer.close();

            Log.d(LOG_TAG, "Writing new " + e.getClass().getName() + " exception to disk.");
        } catch (Exception newException) {
            newException.printStackTrace();
        }
    }

    private static void sendExceptionData(File file) {
        try {
            URL url = new URL(HOPTOAD_ENDPOINT);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            try {
                // Set up the connection
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8");

                // Read from the file and send it
                FileInputStream is = new FileInputStream(file);
                OutputStream os = conn.getOutputStream();
                byte[] buffer = new byte[4096];
                int numRead;
                while((numRead = is.read(buffer)) >= 0) {
                  os.write(buffer, 0, numRead);
                }
                os.flush();
                os.close();
                is.close();

                // Flush the request through
                int response = conn.getResponseCode();
                Log.d(LOG_TAG, "Sent exception file " + file.getName() + " to hoptoad. Got response code " + String.valueOf(response));

                // Delete file now we've sent the exceptions
                file.delete();
            } catch(IOException e) {
                // Ignore any file stream issues
            } finally {
                conn.disconnect();
            }
        } catch(IOException e) {
            // Ignore any connection failure when trying to open the connection
            // We can try again next time
        }
    }

    private static void flushExceptions() {
        File exceptionDir = new File(filePath);
        if(exceptionDir.exists() && exceptionDir.isDirectory()) {
            File[] exceptions = exceptionDir.listFiles();
            for(File f : exceptions) {
                if(f.exists() && f.isFile()) {
                    sendExceptionData(f);
                }
            }
        }
    }
}