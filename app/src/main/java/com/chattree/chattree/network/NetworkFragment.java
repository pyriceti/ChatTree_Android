package com.chattree.chattree.network;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import com.chattree.chattree.login.LoginActivity;
import com.chattree.chattree.tools.Utils;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;

/**
 * Implementation of headless Fragment that runs an AsyncTask to fetch data from the network.
 */
public class NetworkFragment extends Fragment {
    public static final String HTTP_METHOD_GET    = "GET";
    public static final String HTTP_METHOD_POST   = "POST";
    public static final String HTTP_METHOD_PUT    = "PUT";
    public static final String HTTP_METHOD_DELETE = "DELETE";

    private static final String TAG = "NetworkFragment";

    private static final String URL_KEY         = "UrlKey";
    private static final String HTTP_METHOD_KEY = "HttpMethodKey";

    public static final String BASE_URL = "https://24c30071.ngrok.io/";

    private NetConnectCallback<String> mCallback;
    private RequestTask                mRequestTask;
    private String                     mUrlString;
    private String                     mHttpMethod;

    /**
     * Static initializer for NetworkFragment that sets the URL of the host it will be downloading from.
     */
    public static NetworkFragment getInstance(FragmentManager fragmentManager, String url, String httpMethod) {
        NetworkFragment networkFragment = new NetworkFragment();
        Bundle          args            = new Bundle();
        args.putString(URL_KEY, url);
        args.putString(HTTP_METHOD_KEY, httpMethod);
        networkFragment.setArguments(args);
        fragmentManager.beginTransaction().add(networkFragment, TAG).commit();
        return networkFragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mUrlString = getArguments().getString(URL_KEY);
        mHttpMethod = getArguments().getString(HTTP_METHOD_KEY);
    }

    /**
     * Host Activity will handle callbacks from task.
     *
     * @param context The activity or fragment context
     */
    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        Object caller = null;

        switch (getArguments().getString(URL_KEY)) {
            case "login":
                caller = ((LoginActivity) context).getLoginFragment();
                break;
            case "signup":
                caller = ((LoginActivity) context).getSignupFragment();
                break;
        }
        //noinspection unchecked
        mCallback = (NetConnectCallback<String>) caller;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        // Clear reference to host Activity to avoid memory leak.
        mCallback = null;
    }

    @Override
    public void onDestroy() {
        // Cancel task when Fragment is destroyed.
        cancelRequest();
        super.onDestroy();
    }

    /**
     * Start non-blocking execution of RequestTask.
     */
    public void startRequest(String jsonBody) {
        cancelRequest();
        mRequestTask = new RequestTask();
        HttpRequest req = new HttpRequest(mUrlString, mHttpMethod, jsonBody);
        mRequestTask.execute(req);
    }

    /**
     * Cancel (and interrupt if necessary) any ongoing RequestTask execution.
     */
    public void cancelRequest() {
        if (mRequestTask != null) {
            mRequestTask.cancel(true);
        }
    }

    class HttpRequest {
        String url;
        String httpMethod;
        String body;


        HttpRequest(String url, String httpMethod, String jsonBody) {
            this.url = url;
            this.httpMethod = httpMethod;
            this.body = jsonBody;
        }
    }

    /**
     * Implementation of AsyncTask designed to fetch data from the network.
     */
    private class RequestTask extends AsyncTask<HttpRequest, Integer, RequestTask.Result> {

        /**
         * Wrapper class that serves as a union of a result value and an exception. When the
         * download task has completed, either the result value or exception can be a non-null
         * value. This allows you to pass exceptions to the UI thread that were thrown during
         * doInBackground().
         */
        class Result {
            String    mResultValue;
            Exception mException;

            Result(String resultValue) {
                mResultValue = resultValue;
            }

            Result(Exception exception) {
                mException = exception;
            }
        }

        /**
         * Cancel background com.chattree.chattree.network operation if we do not have com.chattree.chattree.network connectivity.
         */
        @Override
        protected void onPreExecute() {
            if (mCallback != null) {
                NetworkInfo networkInfo = mCallback.getActiveNetworkInfo();
                if (networkInfo == null || !networkInfo.isConnected() ||
                    (networkInfo.getType() != ConnectivityManager.TYPE_WIFI
                     && networkInfo.getType() != ConnectivityManager.TYPE_MOBILE)) {
                    // If no connectivity, cancel task and update Callback with null data.
                    mCallback.updateFromRequest(null);
                    cancel(true);
                }
            }
        }

        /**
         * Defines work to perform on the background thread.
         */
        @Override
        protected RequestTask.Result doInBackground(HttpRequest... reqs) {
            Result result = null;
            if (!isCancelled() && reqs != null && reqs.length > 0) {
                HttpRequest req = reqs[0];
                try {
                    URL    url          = new URL(BASE_URL + req.url);
                    String resultString = requestUrl(url, req.httpMethod, req.body);
                    if (resultString != null) {
                        result = new Result(resultString);
                    } else {
                        throw new IOException("No response received.");
                    }
                } catch (Exception e) {
                    result = new Result(e);
                }
            }
            return result;
        }

        /**
         * Updates the DownloadCallback with the result.
         */
        @Override
        protected void onPostExecute(Result result) {
            if (result != null && mCallback != null) {
                if (result.mException != null) {
                    mCallback.updateFromRequest(result.mException.getMessage());
                } else if (result.mResultValue != null) {
                    mCallback.updateFromRequest(result.mResultValue);
                }
                mCallback.finishRequesting();
            }
        }

        /**
         * Override to add special behavior for cancelled AsyncTask.
         */
        @Override
        protected void onCancelled(Result result) {
        }

        /**
         * Given a URL, sets up a connection and gets the HTTP response from the server.
         * If the com.chattree.chattree.network request is successful, it returns the response in String form. Otherwise,
         * it will throw an IOException.
         */
        private String requestUrl(URL url, String httpMethod, String body) throws IOException {
            InputStream        stream     = null;
            HttpsURLConnection connection = null;
            String             result     = null;
            try {
                connection = (HttpsURLConnection) url.openConnection();

                // Timeout for reading InputStream arbitrarily set to 3000ms.
                connection.setReadTimeout(3000);
                // Timeout for connection.connect() arbitrarily set to 3000ms.
                connection.setConnectTimeout(3000);
                // Set HTTP method.
                connection.setRequestMethod(httpMethod);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                SharedPreferences pref  = PreferenceManager.getDefaultSharedPreferences(getContext());
                String            token = pref.getString("token", null);
                connection.setRequestProperty("x-access-token", token);

                // Already true by default but setting just in case; needs to be true since this request
                // is carrying an input (response) body.
                connection.setDoInput(true);

                if (!httpMethod.equals(HTTP_METHOD_GET)) {
                    connection.setDoOutput(true);
                    OutputStream os = connection.getOutputStream();
                    os.write(body.getBytes("UTF-8"));
                    os.close();
                }

                // Open communications link (network traffic occurs here).
                connection.connect();
                publishProgress(NetConnectCallback.Progress.CONNECT_SUCCESS);
                int responseCode = connection.getResponseCode();
                if (responseCode != HttpsURLConnection.HTTP_OK) {
                    throw new IOException("HTTP error code: " + responseCode);
                }
                // Retrieve the response body as an InputStream.
                stream = connection.getInputStream();
                publishProgress(NetConnectCallback.Progress.GET_INPUT_STREAM_SUCCESS, 0);
                if (stream != null) {
                    // Converts Stream to String with max length.
                    result = Utils.readStream(stream, 2000);
                }
            } finally {
                // Close Stream and disconnect HTTPS connection.
                if (stream != null) {
                    stream.close();
                }
                if (connection != null) {
                    connection.disconnect();
                }
            }
            return result;
        }
    }
}
