package com.chattree.chattree.datasync;

import android.accounts.Account;
import android.content.*;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import com.chattree.chattree.db.*;
import com.chattree.chattree.db.Thread;
import com.chattree.chattree.network.NetworkFragment;
import com.chattree.chattree.tools.Utils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.Predicate;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Handle the transfer of data between a server and an
 * app, using the Android sync adapter framework.
 */
public class SyncAdapter extends AbstractThreadedSyncAdapter {
    public static final String EXTRA_SYNC_CONV_ID   = "com.chattree.chattree.CONV ID";
    public static final String EXTRA_SYNC_THREAD_ID = "com.chattree.chattree.THREAD ID";

    public static final String SYNC_CALLBACK_ALL_CONV_LOADED_ACTION = "com.chattree.chattree.ALL_CONV_LOADED_ACTION";
    public static final String SYNC_CALLBACK_CONV_LOADED_ACTION     = "com.chattree.chattree.CONV_LOADED_ACTION";
    public static final String SYNC_CALLBACK_THREAD_LOADED_ACTION   = "com.chattree.chattree.THREAD_LOADED_ACTION";

    private static final SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.CANADA_FRENCH);

    // Global variables
    // Define a variable to contain a content resolver instance
    ContentResolver mContentResolver;

    /**
     * Set up the sync adapter
     */
    public SyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
        /*
         * If your app uses a content resolver, get an instance of it
         * from the incoming Context
         */
        mContentResolver = context.getContentResolver();
    }

    /**
     * Set up the sync adapter. This form of the
     * constructor maintains compatibility with Android 3.0
     * and later platform versions
     */
    public SyncAdapter(
            Context context,
            boolean autoInitialize,
            boolean allowParallelSyncs) {
        super(context, autoInitialize, allowParallelSyncs);
        /*
         * If your app uses a content resolver, get an instance of it
         * from the incoming Context
         */
        mContentResolver = context.getContentResolver();

    }

    /*
     * Specify the code you want to run in the sync adapter. The entire
     * sync adapter runs in a background thread, so you don't have to set
     * up your own background processing.
     */
    @Override
    public void onPerformSync(Account account, Bundle extras, String authority, ContentProviderClient provider, SyncResult syncResult) {
        Log.i("SyncAdapter", "SYNCING DATA...");

        int convId   = extras.getInt(EXTRA_SYNC_CONV_ID, -1);
        int threadId = extras.getInt(EXTRA_SYNC_THREAD_ID, -1);
        if (threadId != -1 && convId != -1) {
            Log.i("SyncAdapter", "SYNCING A THREAD.");
            syncThreadWithLocalDB(convId, threadId);
        } else {
            if (convId != -1) {
                Log.i("SyncAdapter", "SYNCING A CONV.");
                if (extras.getInt("IGNORE_RESULT", -1) != -1) {
                    Log.i("SyncAdapter", "IGNORE THE RESULT.");
                    try {
                        URL url = new URL(NetworkFragment.BASE_URL + "api/get-conv/" + convId);
                        requestUrl(url, NetworkFragment.HTTP_METHOD_GET, null);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else
                    syncConvWithLocalDB(convId);
            } else {
                Log.i("SyncAdapter", "FULL SYNC.");
                fullSync();
            }
        }

    }

    private void fullSync() {

        try {
            URL    url    = new URL(NetworkFragment.BASE_URL + "api/get-conversations");
            String result = requestUrl(url, NetworkFragment.HTTP_METHOD_GET, null);
//            Log.i("SyncAdapter", "GET-CONVERSATIONS : " + result);

            int[] convIds = syncConversationsWithLocalDB(result);

            Intent globalSyncIntent = new Intent();
            globalSyncIntent.setAction(SYNC_CALLBACK_ALL_CONV_LOADED_ACTION);
            getContext().sendBroadcast(globalSyncIntent);

            for (int convId : convIds) {
                int[] threadIds = syncConvWithLocalDB(convId);

                for (int threadId : threadIds) {
                    syncThreadWithLocalDB(convId, threadId);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String requestUrl(URL url, String httpMethod, String body) throws IOException {
        InputStream        stream     = null;
        HttpsURLConnection connection = null;
        String             result     = null;
        try {
            connection = (HttpsURLConnection) url.openConnection();

            // Timeout for reading InputStream arbitrarily set to 10000ms.
            connection.setReadTimeout(10000);
            // Timeout for connection.connect() arbitrarily set to 10000ms.
            connection.setConnectTimeout(10000);
            // Set HTTP method.
            connection.setRequestMethod(httpMethod);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            SharedPreferences pref  = PreferenceManager.getDefaultSharedPreferences(this.getContext());
            String            token = pref.getString("token", null);
            connection.setRequestProperty("x-access-token", token);

            if (body == null)
                connection.setDoOutput(false);
            else
                connection.setDoOutput(true);
            // Already true by default but setting just in case; needs to be true since this request
            // is carrying an input (response) body.
            connection.setDoInput(true);

            if (body != null) {
                OutputStream os = connection.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.close();
            }

            // Open communications link (network traffic occurs here).
            connection.connect();
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpsURLConnection.HTTP_OK) {
                throw new IOException("HTTP error code: " + responseCode);
            }
            // Retrieve the response body as an InputStream.
            stream = connection.getInputStream();
            if (stream != null) {
                // Converts Stream to String with max length.
                result = Utils.readStream(stream, 50000);
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

    /**
     * @param jsonData The conversations with their members as JSON data
     * @return Conv ids
     */
    private int[] syncConversationsWithLocalDB(String jsonData) {

        try {
            JSONObject json          = new JSONObject(jsonData);
            JSONArray  convJsonArray = json.getJSONObject("data").getJSONArray("conversations");

            ConversationDao conversationDao = AppDatabase.getInstance(getContext()).conversationDao();
            UserDao         userDao         = AppDatabase.getInstance(getContext()).userDao();

            ArrayList<Conversation>     convsToInsertOrUpdate = new ArrayList<>();
            ArrayList<User>             usersToInsertOrUpdate = new ArrayList<>();
            ArrayList<ConversationUser> convUsersToInsert     = new ArrayList<>();

            List<Conversation>     localConvs     = conversationDao.findAll();
            List<User>             localUsers     = userDao.findAll();
            List<ConversationUser> localConvUsers = conversationDao.findAllConversationUser();
            int                    convUserIndex  = conversationDao.getConversationUserMaxId();

            int[] convIds = new int[convJsonArray.length()];

            for (int i = 0, size = convJsonArray.length(); i < size; ++i) {
                JSONObject convJsonObj = convJsonArray.getJSONObject(i);
                final int  convId      = convJsonObj.getInt("id");
                convIds[i] = convId;

                int localConvIndex = getConvById(convId, localConvs);
                Conversation receivedConv = new Conversation(
                        convId,
                        convJsonObj.isNull("fk_root_thread") ? null : convJsonObj.getInt("fk_root_thread"),
                        convJsonObj.isNull("title") ? null : convJsonObj.getString("title"),
                        convJsonObj.isNull("picture") ? null : convJsonObj.getString("picture")
                );
                if ((localConvIndex == -1) || (!localConvs.get(localConvIndex).equals(receivedConv))) {
                    //conv does not exist locally or is different
                    convsToInsertOrUpdate.add(receivedConv);
                }
                localConvs.remove(receivedConv);

                JSONArray membersJsonArray = convJsonObj.getJSONArray("members");

                for (int j = 0, membersSize = membersJsonArray.length(); j < membersSize; ++j) {
                    JSONObject memberJsonObj = membersJsonArray.getJSONObject(j);
                    final int  memberId      = memberJsonObj.getInt("id");

                    int localMemberIndex = getUserIndexInListById(memberId, localUsers);
                    User receivedMember = new User(
                            memberId,
                            memberJsonObj.isNull("login") ? null : memberJsonObj.getString("login"),
                            memberJsonObj.isNull("email") ? null : memberJsonObj.getString("email"),
                            memberJsonObj.isNull("firstname") ? null : memberJsonObj.getString("firstname"),
                            memberJsonObj.isNull("lastname") ? null : memberJsonObj.getString("lastname"),
                            memberJsonObj.isNull("profile_picture") ? null : memberJsonObj.getString("profile_picture")
                    );

                    if ((localMemberIndex == -1) || (!localUsers.get(localMemberIndex).equals(receivedMember))) {
                        //member does not exist locally or is different
                        // check member is not already in the list
                        if (!usersToInsertOrUpdate.contains(receivedMember)) {
                            usersToInsertOrUpdate.add(receivedMember);
                        }

                    }
                    // DON'T DO THIS because otherwise the second time a user is found localMemberIndex == -1
                    // since it has been removed from localUsers.
//                    localUsers.remove(receivedMember);


                    ConversationUser receivedConvUser = new ConversationUser(convId, memberId);

                    Collection result = CollectionUtils.select(localConvUsers, new Predicate() {
                        @Override
                        public boolean evaluate(Object object) {
                            ConversationUser convUser = (ConversationUser) object;
                            return convUser.getFk_conversation() == convId
                                   && convUser.getFk_member() == memberId;
                        }
                    });

                    if (result.size() == 0) {
                        receivedConvUser.setId(++convUserIndex);
                        convUsersToInsert.add(receivedConvUser);
                    }
                    localConvUsers.remove(receivedConvUser);
                }
            }

            //DELETES - NO DELETES FOR NOW (FOREIGN KEYS...)
            /*conversationDao.deleteConversationUsers(localConvUsers);
            System.out.println(localConvUsers.size()+" conv users deleted");
            conversationDao.deleteAll(localConvs);
            System.out.println(localConvs.size() + " convs deleted");
            userDao.deleteAll(localUsers);
            System.out.println(localUsers.size() + " users deleted");*/

            //INSERTS OR UPDATES
            conversationDao.insertAll(convsToInsertOrUpdate);
            System.out.println(convsToInsertOrUpdate.size() + " convs inserted");
            userDao.insertAll(usersToInsertOrUpdate);
            System.out.println(usersToInsertOrUpdate.size() + " users inserted");
            conversationDao.insertConversationUsers(convUsersToInsert);
            System.out.println(convUsersToInsert.size() + " conv users inserted");

            return convIds;

        } catch (JSONException e) {
            e.printStackTrace();
        }

        return null;
    }

    // Returns thread ids
    private int[] syncConvWithLocalDB(int convId) {
        try {
            URL    url    = new URL(NetworkFragment.BASE_URL + "api/get-conv/" + convId);
            String result = requestUrl(url, NetworkFragment.HTTP_METHOD_GET, null);
//            Log.i("SyncAdapter", "GET-CONV/" + convId + " : " + result);

            JSONObject json            = new JSONObject(result);
            JSONObject convJson        = json.getJSONObject("data").getJSONObject("conversation");
            JSONArray  threadJsonArray = convJson.getJSONArray("threads");

            ConversationDao conversationDao = AppDatabase.getInstance(getContext()).conversationDao();
            ThreadDao       threadDao       = AppDatabase.getInstance(getContext()).threadDao();

            ArrayList<Thread> threadsToInsertOrUpdate = new ArrayList<>();

            List<Thread> localThreads = threadDao.findAll();

            int[] threadIds = new int[threadJsonArray.length()];

            // Let's insert threads
            for (int i = 0, size = threadJsonArray.length(); i < size; ++i) {
                JSONObject threadJsonObj = threadJsonArray.getJSONObject(i);
                int        threadId      = threadJsonObj.getInt("id");
                threadIds[i] = threadId;
                Thread receivedThread = new Thread(
                        threadId,
                        threadJsonObj.isNull("date") ? null : simpleDateFormat.parse(threadJsonObj.getString("date").replaceAll("Z$", "+0000")),
                        threadJsonObj.isNull("title") ? null : threadJsonObj.getString("title"),
                        threadJsonObj.isNull("author") ? null : threadJsonObj.getInt("author"),
                        threadJsonObj.isNull("conversation") ? null : threadJsonObj.getInt("conversation"),
                        threadJsonObj.isNull("thread_parent") ? null : threadJsonObj.getInt("thread_parent"),
//                        threadJsonObj.isNull("message_parent") ? null : threadJsonObj.getInt("message_parent")
                        null // TODO: find how to correctly update the fk_message_parent (fk exception
                        // is thrown for now because we insert the messages AFTER the threads
                );
                int localThreadIndex = getThreadIndexInListById(threadId, localThreads);
                if ((localThreadIndex == -1) || (!localThreads.get(localThreadIndex).equals(receivedThread))) {
                    // Need to insert or update
                    threadsToInsertOrUpdate.add(receivedThread);
                }

            }

            // Sort the threads so we are sure to insert them in the right order (according to their fks -> parents)
            Collections.sort(threadsToInsertOrUpdate, new Comparator<Thread>() {
                @Override
                public int compare(Thread t1, Thread t2) {
                    return t1.getId() < t2.getId() ? -1 : 1;
                }
            });

            // INSERTS OR UPDATES
            threadDao.insertAll(threadsToInsertOrUpdate);
//            System.out.println(threadsToInsertOrUpdate.size() + " threads inserted");

            // Then let's update conversation.fk_root_thread
            if (!convJson.isNull("fk_root_thread")) {
                int fkrt = convJson.getInt("fk_root_thread");
                conversationDao.updateFKRootThreadById(fkrt, convId);
//                System.out.println("fk_root_thread updated, fk = " + fkrt + ", convId = " + convId);
            }

            Intent convSyncIntent = new Intent();
            convSyncIntent.setAction(SYNC_CALLBACK_CONV_LOADED_ACTION);
            convSyncIntent.putExtra(EXTRA_SYNC_CONV_ID, convId);
            getContext().sendBroadcast(convSyncIntent);

            return threadIds;

        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private void syncThreadWithLocalDB(int convId, int threadId) {
        try {
            URL    url    = new URL(NetworkFragment.BASE_URL + "api/get-thread/" + convId + "/" + threadId);
            String result = requestUrl(url, NetworkFragment.HTTP_METHOD_GET, null);
//            Log.i("SyncAdapter", "GET-THREAD/" + convId + "/" + threadId + " : " + result);

            JSONObject json             = new JSONObject(result);
            JSONArray  messageJsonArray = json.getJSONObject("data").getJSONArray("messages");

            MessageDao messageDao = AppDatabase.getInstance(getContext()).messageDao();

            ArrayList<Message> messagesToInsertOrUpdate = new ArrayList<>();

            List<Message> localMessages = messageDao.findAll();

            for (int i = 0; i < messageJsonArray.length(); i++) {
                JSONObject messJsonObj = messageJsonArray.getJSONObject(i);
                int        messId      = messJsonObj.getInt("id");
                Message receivedMess = new Message(
                        messId,
                        messJsonObj.getInt("author"),
                        messJsonObj.isNull("date") ? null : simpleDateFormat.parse(messJsonObj.getString("date").replaceAll("Z$", "+0000")),
                        messJsonObj.getString("content"),
                        messJsonObj.getInt("thread")
                );
                int localMessIndex = getMessageIndexInListById(messId, localMessages);
                if ((localMessIndex == -1) || (!localMessages.get(localMessIndex).equals(receivedMess))) {
                    //need to insert or update
                    messagesToInsertOrUpdate.add(receivedMess);
                }
            }

            //INSERTS OR UPDATES
            messageDao.insertAll(messagesToInsertOrUpdate);
//            System.out.println(messagesToInsertOrUpdate.size() + " messages inserted or updated");

            Intent threadSyncIntent = new Intent();
            threadSyncIntent.setAction(SYNC_CALLBACK_THREAD_LOADED_ACTION);
            threadSyncIntent.putExtra(EXTRA_SYNC_CONV_ID, convId);
            threadSyncIntent.putExtra(EXTRA_SYNC_THREAD_ID, threadId);
            getContext().sendBroadcast(threadSyncIntent);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private int getConvById(int id, List<Conversation> l) {
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).getId() == id)
                return i;
        }
        return -1;
    }

    private int getUserIndexInListById(int id, List<User> l) {
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).getId() == id)
                return i;
        }
        return -1;
    }

    private int getThreadIndexInListById(int id, List<Thread> l) {
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).getId() == id)
                return i;
        }
        return -1;
    }

    private int getMessageIndexInListById(int id, List<Message> l) {
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).getId() == id)
                return i;
        }
        return -1;
    }
}
