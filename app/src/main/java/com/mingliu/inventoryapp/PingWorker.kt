package com.mingliu.inventoryapp

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

private const val TAG = "PingWorker"

/**
 * Background worker that periodically pings the backend's /api/ping
 * endpoint to keep a free-tier host (e.g. Render) from going idle.
 */
class PingWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {

    override fun doWork(): Result {
        Log.d(TAG, "Sending scheduled keep-alive ping")

        // No auth token needed here; this only exists to keep the server warm.
        RetrofitClient.instance.pingServer().enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                if (response.isSuccessful) {
                    Log.i(TAG, "Ping succeeded")
                } else {
                    Log.w(TAG, "Ping returned non-2xx status: ${response.code()}")
                }
            }

            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                Log.e(TAG, "Ping failed: ${t.localizedMessage}")
            }
        })

        return Result.success()
    }
}
