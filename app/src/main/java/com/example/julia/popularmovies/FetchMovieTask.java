package com.example.julia.popularmovies;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.net.Uri;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import com.example.julia.popularmovies.data.MovieContract.MovieEntry;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Vector;

class FetchMovieTask extends AsyncTask<Void, Void, Movie[]> {

    private final String LOG_TAG = FetchMovieTask.class.getSimpleName();
    private Context mContext;
    private MovieAdapter mMovieAdapter;

    FetchMovieTask(Context context, MovieAdapter movieAdapter) {
        mContext = context;
        mMovieAdapter = movieAdapter;
    }

    /* The date/time conversion code is going to be moved outside the asynctask later,
     * so for convenience we're breaking it out into its own method now.
     */
    private String getReadableDateString(String d){
        // TODO: code here
        //Date date = new Date(d);
        //SimpleDateFormat format = new SimpleDateFormat("E, MMM d");
        return ""; // format.format(date).toString();
    }

    private Movie[] getMovieDataFromJson(String movieJsonStr) throws JSONException {
        try {
            JSONObject movieJson = new JSONObject(movieJsonStr);
            JSONArray movieArray = movieJson.getJSONArray(Config.TMD_LIST);
            int moviesLength = movieArray.length();
            // Insert the new weather information into the database
            Vector<ContentValues> cVVector = new Vector<>(moviesLength);

            Movie[] resultMovies = new Movie[moviesLength];

            for (int i = 0; i < moviesLength; i++) {
                JSONObject movie = movieArray.getJSONObject(i);
                String posterUrl = Config.MOVIE_POSTER_BASE_URL + movie.getString(Config.TMD_POSTER);

                resultMovies[i] = new Movie(
                        movie.getString(Config.TMD_ID),
                        posterUrl,
                        movie.getString(Config.TMD_TITLE),
                        movie.getString(Config.TMD_DATE),
                        movie.getString(Config.TMD_RATING),
                        movie.getString(Config.TMD_SYNOPSIS)
                );

                ContentValues movieValues = new ContentValues();

                movieValues.put(MovieEntry.COLUMN_MOVIE_ID, movie.getString(Config.TMD_ID));
                movieValues.put(MovieEntry.COLUMN_TITLE, movie.getString(Config.TMD_TITLE));
                movieValues.put(MovieEntry.COLUMN_DATE, movie.getString(Config.TMD_DATE));
                movieValues.put(MovieEntry.COLUMN_SYNOPSIS, movie.getString(Config.TMD_SYNOPSIS));
                movieValues.put(MovieEntry.COLUMN_POSTER, movie.getString(Config.TMD_POSTER));
                movieValues.put(MovieEntry.COLUMN_RATING, movie.getString(Config.TMD_RATING));

                cVVector.add(movieValues);

            }
            // add to database
            if ( cVVector.size() > 0 ) {
                ContentValues[] cvArray = new ContentValues[cVVector.size()];
                cVVector.toArray(cvArray);
                mContext.getContentResolver().bulkInsert(MovieEntry.CONTENT_URI, cvArray);
            }

            Cursor cur = mContext.getContentResolver().query(MovieEntry.CONTENT_URI, null, null, null, null);

            cVVector = new Vector<>(cur.getCount());
            if ( cur.moveToFirst() ) {
                do {
                    ContentValues cv = new ContentValues();
                    DatabaseUtils.cursorRowToContentValues(cur, cv);
                    cVVector.add(cv);
                } while (cur.moveToNext());
            }

            Log.d(LOG_TAG, "FetchMovieTask Complete. " + cVVector.size() + " Inserted");

            return resultMovies;
        } catch (JSONException e) {
            Log.e(LOG_TAG, e.getMessage(), e);
            e.printStackTrace();
        }
        return null;
    }

    private String sortBy() {
        SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(mContext);
        String sortType = prefs.getString(
                mContext.getString(R.string.pref_sort_key),
                mContext.getString(R.string.pref_sort_popular));
        String sort = "popular";
        if (sortType.equals(mContext.getString(R.string.pref_sort_rating))) {
            sort = "top_rated";
        } else if (!sortType.equals(mContext.getString(R.string.pref_sort_popular))) {
            Log.d(LOG_TAG, "Unit type not found: " + sortType);
        }
        return sort;
    }

    @Override
    protected Movie[] doInBackground(Void... params) {
        // These two need to be declared outside the try/catch
        // so that they can be closed in the finally block.
        HttpURLConnection urlConnection = null;
        BufferedReader reader = null;

        // Will contain the raw JSON response as a string.
        String movieJsonStr = null;
        try {
            String sort_by = sortBy();

            Uri builtUri = Uri.parse(Config.MOVIE_BASE_URL).buildUpon()
                    .appendPath(sort_by)
                    .appendQueryParameter(Config.API_KEY_PARAM, BuildConfig.THE_MOVIE_DB_API_KEY)
                    .build();

            URL url = new URL(builtUri.toString());
            // Create the request to OpenWeatherMap, and open the connection
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestMethod("GET");
            urlConnection.connect();

            // Read the input stream into a String
            InputStream inputStream = urlConnection.getInputStream();
            StringBuilder buffer = new StringBuilder();
            if (inputStream == null) {
                // Nothing to do.
                return null;
            }
            reader = new BufferedReader(new InputStreamReader(inputStream));

            String line;
            while ((line = reader.readLine()) != null) {
                // Since it's JSON, adding a newline isn't necessary (it won't affect parsing)
                // But it does make debugging a *lot* easier if you print out the completed
                // buffer for debugging.
                buffer.append(line).append("\n");
            }

            if (buffer.length() == 0) {
                // Stream was empty.  No point in parsing.
                return null;
            }
            movieJsonStr = buffer.toString();
        } catch (IOException e) {
            Log.e("MovieFragment", "Error ", e);
            // If the code didn't successfully get the weather data, there's no point in attemping
            // to parse it.
            return null;
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            if (reader != null) {
                try {
                    reader.close();
                } catch (final IOException e) {
                    Log.e("MovieFragment", "Error closing stream", e);
                }
            }
        }

        try {
            return getMovieDataFromJson(movieJsonStr);
        } catch (JSONException e) {
            Log.e(LOG_TAG, e.getMessage(), e);
            e.printStackTrace();
        }
        // This will only happen if there was an error getting or parsing the movies.
        return null;
    }

    @Override
    protected void onPostExecute(Movie[] result) {
        if (result != null && mMovieAdapter != null) {
            mMovieAdapter.clear();
            for (Movie movie: result) {
                mMovieAdapter.add(movie);
            }
        } else {
            Toast.makeText(
                    mContext,
                    "Something went wrong, please check your internet connection and try again!",
                    Toast.LENGTH_SHORT)
                    .show();
        }
    }
}