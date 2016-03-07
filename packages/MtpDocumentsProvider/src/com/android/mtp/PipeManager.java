/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.mtp;

import android.content.Context;
import android.mtp.MtpObjectInfo;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class PipeManager {
    /**
     * Milliseconds we wait for background thread when pausing.
     */
    private final static long AWAIT_TERMINATION_TIMEOUT = 2000;

    final ExecutorService mExecutor;
    final MtpDatabase mDatabase;

    PipeManager(MtpDatabase database) {
        this(database, Executors.newSingleThreadExecutor());
    }

    PipeManager(MtpDatabase database, ExecutorService executor) {
        this.mDatabase = database;
        this.mExecutor = executor;
    }

    ParcelFileDescriptor readDocument(MtpManager model, Identifier identifier) throws IOException {
        final Task task = new ImportFileTask(model, identifier);
        mExecutor.execute(task);
        return task.getReadingFileDescriptor();
    }

    ParcelFileDescriptor writeDocument(Context context, MtpManager model, Identifier identifier,
                                       int[] operationsSupported)
            throws IOException {
        final Task task = new WriteDocumentTask(
                context, model, identifier, operationsSupported, mDatabase);
        mExecutor.execute(task);
        return task.getWritingFileDescriptor();
    }

    ParcelFileDescriptor readThumbnail(MtpManager model, Identifier identifier) throws IOException {
        final Task task = new GetThumbnailTask(model, identifier);
        mExecutor.execute(task);
        return task.getReadingFileDescriptor();
    }

    private static abstract class Task implements Runnable {
        protected final MtpManager mManager;
        protected final Identifier mIdentifier;
        protected final ParcelFileDescriptor[] mDescriptors;

        Task(MtpManager manager, Identifier identifier) throws IOException {
            mManager = manager;
            mIdentifier = identifier;
            mDescriptors = ParcelFileDescriptor.createReliablePipe();
        }

        ParcelFileDescriptor getReadingFileDescriptor() {
            return mDescriptors[0];
        }

        ParcelFileDescriptor getWritingFileDescriptor() {
            return mDescriptors[1];
        }
    }

    private static class ImportFileTask extends Task {
        ImportFileTask(MtpManager model, Identifier identifier) throws IOException {
            super(model, identifier);
        }

        @Override
        public void run() {
            try {
                mManager.importFile(
                        mIdentifier.mDeviceId, mIdentifier.mObjectHandle, mDescriptors[1]);
                mDescriptors[1].close();
            } catch (IOException error) {
                try {
                    mDescriptors[1].closeWithError("Failed to stream a file.");
                } catch (IOException closeError) {
                    Log.w(MtpDocumentsProvider.TAG, closeError.getMessage());
                }
            }
        }
    }

    private static class WriteDocumentTask extends Task {
        private final Context mContext;
        private final MtpDatabase mDatabase;
        private final int[] mOperationsSupported;

        WriteDocumentTask(Context context,
                          MtpManager model,
                          Identifier identifier,
                          int[] supportedOperations,
                          MtpDatabase database)
                throws IOException {
            super(model, identifier);
            mContext = context;
            mDatabase = database;
            mOperationsSupported = supportedOperations;
        }

        @Override
        public void run() {
            File tempFile = null;
            try {
                // Obtain a temporary file and copy the data to it.
                tempFile = File.createTempFile("mtp", "tmp", mContext.getCacheDir());
                try (
                    final FileOutputStream tempOutputStream =
                            new ParcelFileDescriptor.AutoCloseOutputStream(
                                    ParcelFileDescriptor.open(
                                            tempFile, ParcelFileDescriptor.MODE_WRITE_ONLY));
                    final ParcelFileDescriptor.AutoCloseInputStream inputStream =
                            new ParcelFileDescriptor.AutoCloseInputStream(mDescriptors[0])
                ) {
                    final byte[] buffer = new byte[32 * 1024];
                    int bytes;
                    while ((bytes = inputStream.read(buffer)) != -1) {
                        mDescriptors[0].checkError();
                        tempOutputStream.write(buffer, 0, bytes);
                    }
                    tempOutputStream.flush();
                }

                // Get the placeholder object info.
                final MtpObjectInfo placeholderObjectInfo =
                        mManager.getObjectInfo(mIdentifier.mDeviceId, mIdentifier.mObjectHandle);

                // Delete the target object info if it already exists (as a placeholder).
                mManager.deleteDocument(mIdentifier.mDeviceId, mIdentifier.mObjectHandle);

                // Create the target object info with a correct file size and upload the file.
                final MtpObjectInfo targetObjectInfo =
                        new MtpObjectInfo.Builder(placeholderObjectInfo)
                                .setCompressedSize(tempFile.length())
                                .build();
                final ParcelFileDescriptor tempInputDescriptor = ParcelFileDescriptor.open(
                        tempFile, ParcelFileDescriptor.MODE_READ_ONLY);
                final int newObjectHandle = mManager.createDocument(
                        mIdentifier.mDeviceId, targetObjectInfo, tempInputDescriptor);

                final MtpObjectInfo newObjectInfo = mManager.getObjectInfo(
                        mIdentifier.mDeviceId, newObjectHandle);
                final Identifier parentIdentifier =
                        mDatabase.getParentIdentifier(mIdentifier.mDocumentId);
                mDatabase.updateObject(
                        mIdentifier.mDocumentId,
                        mIdentifier.mDeviceId,
                        parentIdentifier.mDocumentId,
                        mOperationsSupported,
                        newObjectInfo);
            } catch (IOException error) {
                Log.w(MtpDocumentsProvider.TAG,
                        "Failed to send a file because of: " + error.getMessage());
            } finally {
                if (tempFile != null) {
                    tempFile.delete();
                }
            }
        }
    }

    private static class GetThumbnailTask extends Task {
        GetThumbnailTask(MtpManager model, Identifier identifier) throws IOException {
            super(model, identifier);
        }

        @Override
        public void run() {
            try {
                try (final ParcelFileDescriptor.AutoCloseOutputStream stream =
                        new ParcelFileDescriptor.AutoCloseOutputStream(mDescriptors[1])) {
                    try {
                        stream.write(mManager.getThumbnail(
                                mIdentifier.mDeviceId, mIdentifier.mObjectHandle));
                    } catch (IOException error) {
                        mDescriptors[1].closeWithError("Failed to stream a thumbnail.");
                    }
                }
            } catch (IOException closeError) {
                Log.w(MtpDocumentsProvider.TAG, closeError.getMessage());
            }
        }
    }

    boolean close() throws InterruptedException {
        mExecutor.shutdownNow();
        return mExecutor.awaitTermination(AWAIT_TERMINATION_TIMEOUT, TimeUnit.MILLISECONDS);
    }
}
