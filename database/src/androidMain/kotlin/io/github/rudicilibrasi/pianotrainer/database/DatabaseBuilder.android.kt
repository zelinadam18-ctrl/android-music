package io.github.rudicilibrasi.pianotrainer.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

/** The database in the app's private databases folder. Pass the result to [buildPianoDatabase]. */
fun databaseBuilder(context: Context): RoomDatabase.Builder<PianoDatabase> {
    val appContext = context.applicationContext
    return Room.databaseBuilder<PianoDatabase>(
        context = appContext,
        name = appContext.getDatabasePath(PianoDatabase.FILE_NAME).absolutePath,
    )
}
