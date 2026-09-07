package com.dx.ghostmessenger.file;

import android.database.Cursor;
import android.util.Log;

import com.dx.ghostmessenger.DxApplication;
import com.dx.ghostmessenger.account.DxAccount;
import com.dx.ghostmessenger.util.Hex;
import com.dx.ghostmessenger.util.Utils;

import net.sqlcipher.database.SQLiteDatabase;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class BackupHelper {
    private static final String TAG = "BackupHelper";

    // Backup file format constants (UTF-8 is always available, no exception possible)
    private static final byte[] MAGIC = "ANMB".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final int VERSION = 1;
    private static final int CHECKSUM_LENGTH = 32;
    private static final int HEADER_SIZE = MAGIC.length + 2 + CHECKSUM_LENGTH + 8;

    // Encryption constants (match FileHelper)
    private static final int IV_LENGTH = 12;
    private static final int CHUNK_SIZE = 512 * 1024;
    // Max allowed chunk size during decrypt (DOS protection: 64MB per chunk)
    private static final int MAX_CHUNK_SIZE = 64 * 1024 * 1024;

    // ZIP buffer
    private static final int BUFFER_SIZE = 8192;


    /**
     * Progress callback for backup/restore operations.
     */
    public interface ProgressListener {
        void onProgress(int percent, String message);
        void onComplete(boolean success, String message);
    }


    // ==================== PUBLIC API ====================

    /**
     * Creates an encrypted backup of all critical app data.
     *
     * Flow: rekey DB/files to new password → close DB → zip data → stream-encrypt → write .abk → reopen DB
     *
     * @param app           The application
     * @param encryptionKey SHA-256 derived key (32 bytes) for zip encryption
     * @param rawPassword   New password for DB and file rekeying (may be null to keep current password)
     * @param outputDir     Directory to save the backup file
     * @param listener      Progress callback (may be null)
     * @return Path to the created backup file, or null on failure
     */
    public static String createBackup(DxApplication app, byte[] encryptionKey,
                                      byte[] rawPassword, File outputDir, ProgressListener listener) {
        return createBackup(app, encryptionKey, rawPassword, outputDir, listener, false);
    }

    /**
     * Creates an encrypted backup of all critical app data.
     *
     * Flow: rekey DB/files to new password → close DB → zip data → stream-encrypt → write .abk → reopen DB
     *
     * @param app           The application
     * @param encryptionKey SHA-256 derived key (32 bytes) for zip encryption
     * @param rawPassword   New password for DB and file rekeying (may be null to keep current password)
     * @param outputDir     Directory to save the backup file
     * @param listener      Progress callback (may be null)
     * @param includeEncryptedFiles If true, rekey and include encrypted chat files
     * @return Path to the created backup file, or null on failure
     */
    public static String createBackup(DxApplication app, byte[] encryptionKey,
                                      byte[] rawPassword, File outputDir, ProgressListener listener,
                                      boolean includeEncryptedFiles) {
        if (app == null || encryptionKey == null || outputDir == null) {
            Log.e(TAG, "Invalid parameters for backup");
            if (listener != null) listener.onComplete(false, "Invalid parameters");
            return null;
        }

        File tempZipFile = null;
        File encryptedBackupFile = null;
        File rekeyedDbCopy = null;
        File rekeyedFilesDir = null;

        try {
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                Log.e(TAG, "Failed to create output directory: " + outputDir);
                if (listener != null) listener.onComplete(false, "Output directory creation failed");
                return null;
            }

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            tempZipFile = new File(app.getCacheDir(), "backup_temp_" + timestamp + ".zip");
            encryptedBackupFile = new File(outputDir, "GhostMessenger_Backup_" + timestamp + ".abk");

            // STEP 0: Copy and rekey database to new password (if provided)
            if (rawPassword != null) {
                if (listener != null) listener.onProgress(2, "Preparing encrypted backup copy...");
                rekeyedDbCopy = copyAndRekeyDatabase(app, rawPassword);
                if (rekeyedDbCopy == null) {
                    Log.e(TAG, "[BACKUP] STEP 0 FAIL: Could not prepare database copy");
                    if (listener != null) listener.onComplete(false, "Failed to prepare database for backup");
                    return null;
                }
                Log.d(TAG, "[BACKUP] STEP 0 OK: Database copy rekeyed");
            }

            // STEP 0.5: Rekey encrypted files if requested
            if (includeEncryptedFiles && rawPassword != null) {
                if (listener != null) listener.onProgress(5, "Rekeying encrypted files...");
                rekeyedFilesDir = rekeyEncryptedFiles(app, rawPassword);
                if (rekeyedFilesDir == null) {
                    Log.e(TAG, "[BACKUP] STEP 0.5 FAIL: Could not rekey encrypted files");
                    if (listener != null) listener.onComplete(false, "Failed to rekey encrypted files");
                    return null;
                }
                Log.d(TAG, "[BACKUP] STEP 0.5 OK: Encrypted files rekeyed");
            }

            // STEP 1: Close database for a clean snapshot (synchronized to avoid race)
            if (listener != null) listener.onProgress(5, "Closing database...");
            closeAndClearDb(app);
            Log.d(TAG, "[BACKUP] STEP 1 OK: DB closed");

            // STEP 2: Create ZIP of all data
            if (listener != null) listener.onProgress(10, "Collecting data...");
            zipAllData(app, tempZipFile, listener, rekeyedDbCopy, rekeyedFilesDir);

            long zipSize = tempZipFile.length();
            Log.d(TAG, "[BACKUP] STEP 2 OK: ZIP size = " + zipSize + " bytes");
            if (zipSize == 0) {
                Log.e(TAG, "[BACKUP] STEP 2 FAIL: ZIP file is empty");
                if (listener != null) listener.onComplete(false, "ZIP file is empty");
                cleanup(tempZipFile);
                try {
                    reopenDb(app, app.getAccount(), null);
                } catch (Exception e) {
                    Log.e(TAG, "Db reopen failed: " + e.getMessage());
                }
                return null;
            }

            // STEP 3: Compute checksum of original ZIP data
            if (listener != null) listener.onProgress(50, "Computing integrity check...");
            byte[] checksum = sha256File(tempZipFile);
            Log.d(TAG, "[BACKUP] STEP 3 OK: checksum = " + Hex.toStringCondensed(checksum));

            // STEP 4: Stream-encrypt ZIP → .abk
            if (listener != null) listener.onProgress(55, "Encrypting backup...");
            streamEncryptZip(tempZipFile, encryptedBackupFile, encryptionKey, checksum, zipSize, listener);
            Log.d(TAG, "[BACKUP] STEP 4 OK: Encrypted file size = " + encryptedBackupFile.length() + " bytes");

            // Clean up rekeyed DB copy after successful encryption
            if (rekeyedDbCopy != null) {
                deleteTempDatabase(rekeyedDbCopy);
                rekeyedDbCopy = null;
            }

            // Clean up rekeyed files directory
            if (rekeyedFilesDir != null) {
                deleteDirectory(rekeyedFilesDir);
                rekeyedFilesDir = null;
            }

            // STEP 5: Reopen database
            if (listener != null) listener.onProgress(98, "Reopening database...");
            try {
                reopenDb(app, app.getAccount(), null);
            } catch (Exception e) {
                Log.e(TAG, "Db reopen failed: " + e.getMessage());
            }
            Log.d(TAG, "[BACKUP] STEP 5 OK: DB reopened");

            // Cleanup temp
            cleanup(tempZipFile);

            Log.i(TAG, "[BACKUP] SUCCESS: " + encryptedBackupFile.getAbsolutePath() +
                    " (" + formatSize(encryptedBackupFile.length()) + ")");
            if (listener != null) {
                listener.onProgress(100, "Done");
                listener.onComplete(true, "Backup saved to " + outputDir.getName());
            }
            return encryptedBackupFile.getAbsolutePath();

        } catch (Exception e) {
            Log.e(TAG, "Backup failed: " + e.getMessage(), e);
            try {
                reopenDb(app, app.getAccount(), null);
            } catch (Exception e2) {
                Log.e(TAG, "Db reopen failed: " + e2.getMessage());
            }
            if (listener != null) listener.onComplete(false, "Backup failed: " + e.getMessage());
            return null;
        } finally {
            cleanup(tempZipFile);
            if (rekeyedDbCopy != null) {
                deleteTempDatabase(rekeyedDbCopy);
            }
            if (rekeyedFilesDir != null) {
                deleteDirectory(rekeyedFilesDir);
            }
        }
    }

    /**
     * Restores app data from an encrypted backup file.
     *
     * Flow: close DB → decrypt .abk → unzip → restore files → reopen DB → verify
     *
     * @param app              The application
     * @param backupFilePath   Path to the .abk backup file
     * @param encryptionKey    SHA-256 derived key (32 bytes) for zip decryption
     * @param rawPassword      New DB password (may be null to keep current password)
     * @param listener         Progress callback (may be null)
     * @return true if restore succeeded completely
     */
    public static boolean restoreBackup(DxApplication app, String backupFilePath,
                                            byte[] encryptionKey, byte[] rawPassword, ProgressListener listener) {
        File backupFile = new File(backupFilePath);
        if (!backupFile.exists()) {
            Log.e(TAG, "[RESTORE] STEP 0 FAIL: Backup file not found: " + backupFilePath);
            if (listener != null) listener.onComplete(false, "Backup file not found");
            return false;
        }
        Log.d(TAG, "[RESTORE] Backup file size: " + backupFile.length() + " bytes");

        File tempZipFile = null;
        File originalDbBackup = null;
        boolean wasDbOpen = false;
        DxAccount savedAccount = app.getAccount();
        HeaderInfo header = null;

        try {
            boolean isNewFormat = checkNewFormat(backupFile);
            if (!isNewFormat) {
                Log.i(TAG, "[RESTORE] STEP 1: Legacy backup format detected");
                if (listener != null) listener.onProgress(5, "Legacy backup format detected...");
            } else {
                if (listener != null) listener.onProgress(5, "Reading backup header...");
                header = readHeader(backupFile);
                if (header == null) {
                    Log.e(TAG, "[RESTORE] STEP 1 FAIL: Header read returned null");
                    if (listener != null) listener.onComplete(false, "Invalid backup file format");
                    return false;
                }
            }

            // Back up original database before closing (for rollback)
            originalDbBackup = backupOriginalDatabase(app);

            if (listener != null) listener.onProgress(15, "Closing database...");
            wasDbOpen = closeAndClearDb(app);

            if (listener != null) listener.onProgress(20, "Decrypting backup...");
            tempZipFile = new File(app.getCacheDir(), "restore_temp_" + System.currentTimeMillis() + ".zip");
            
            if (isNewFormat) {
                streamDecryptBackup(backupFile, tempZipFile, encryptionKey, header, listener);
            } else {
                streamDecryptLegacyBackup(backupFile, tempZipFile, encryptionKey, listener);
            }

            if (isNewFormat) {
                if (listener != null) listener.onProgress(60, "Verifying integrity...");
                byte[] actualChecksum = sha256File(tempZipFile);
                if (!MessageDigest.isEqual(header.checksum, actualChecksum)) {
                    Log.e(TAG, "[RESTORE] STEP 4 FAIL: Checksum mismatch");
                    if (listener != null) listener.onComplete(false, "Backup data corrupted");
                    rollbackRestore(app, originalDbBackup, savedAccount);
                    return false;
                }
            }

            if (listener != null) listener.onProgress(65, "Restoring data...");
            extractAndRestore(app, tempZipFile, listener);

            if (listener != null) listener.onProgress(85, "Loading account from backup...");
            DxAccount restoredAccount = loadAccountFromZip(tempZipFile, savedAccount);
            if (restoredAccount != null) {
                app.setAccount(restoredAccount);
            } else if (savedAccount != null) {
                app.setAccount(savedAccount);
            }

            if (listener != null) listener.onProgress(90, "Reopening database...");
            reopenDb(app, app.getAccount() != null ? app.getAccount() : savedAccount, rawPassword);

            SQLiteDatabase restoredDb = app.getDb();
            if (restoredDb == null || !restoredDb.isOpen()) {
                Log.e(TAG, "[RESTORE] STEP 8 FAIL: restoredDb=" + restoredDb);
                if (listener != null) listener.onComplete(false, "Database error after restore");
                rollbackRestore(app, originalDbBackup, savedAccount);
                return false;
            }

            if (rawPassword != null && app.getAccount() != null) {
                app.getAccount().setPassword(rawPassword);
            }
            cleanup(tempZipFile);
            deleteFile(originalDbBackup);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Restore failed: " + e.getMessage(), e);
            if (listener != null) listener.onComplete(false, "Restore failed: " + e.getMessage());
            rollbackRestore(app, originalDbBackup, savedAccount);
            return false;
        } finally {
            if (tempZipFile != null) cleanup(tempZipFile);
            deleteFile(originalDbBackup);
        }
    }

    private static File backupOriginalDatabase(DxApplication app) {
        try {
            File originalDb = new File(app.getFilesDir(), "demo.db");
            if (!originalDb.exists()) return null;
            
            File backup = new File(app.getCacheDir(), "restore_original_db_backup_" + System.currentTimeMillis() + ".db");
            try (FileInputStream fis = new FileInputStream(originalDb);
                 FileOutputStream fos = new FileOutputStream(backup)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = fis.read(buf)) != -1) {
                    fos.write(buf, 0, len);
                }
                fos.flush();
            }
            Log.d(TAG, "[RESTORE] Original database backed up to: " + backup.getAbsolutePath());
            return backup;
        } catch (Exception e) {
            Log.w(TAG, "Failed to backup original database: " + e.getMessage());
            return null;
        }
    }

    private static void rollbackRestore(DxApplication app, File originalDbBackup, DxAccount savedAccount) {
        // Clean up any partially restored temp files
        try {
            File[] tempDirs = app.getCacheDir().listFiles((dir, name) -> name.startsWith("restore_temp_files_"));
            if (tempDirs != null) {
                for (File tempDir : tempDirs) {
                    deleteDirectory(tempDir);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to clean up temp restore files: " + e.getMessage());
        }

        if (originalDbBackup != null && originalDbBackup.exists()) {
            try {
                File targetDb = new File(app.getFilesDir(), "demo.db");
                if (targetDb.exists()) targetDb.delete();
                
                try (FileInputStream fis = new FileInputStream(originalDbBackup);
                     FileOutputStream fos = new FileOutputStream(targetDb)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = fis.read(buf)) != -1) {
                        fos.write(buf, 0, len);
                    }
                    fos.flush();
                }
                
                if (savedAccount != null && savedAccount.getPassword() != null) {
                    app.setAccount(savedAccount);
                    try {
                        reopenDb(app, savedAccount, null);
                        Log.d(TAG, "[RESTORE] Rolled back to original database successfully");
                    } catch (Exception e) {
                        Log.e(TAG, "[RESTORE] Failed to reopen original database after rollback: " + e.getMessage());
                    }
                }
                deleteFile(originalDbBackup);
            } catch (Exception e) {
                Log.e(TAG, "[RESTORE] Rollback failed: " + e.getMessage());
                deleteFile(originalDbBackup);
            }
        } else {
            if (savedAccount != null) {
                app.setAccount(savedAccount);
            }
        }
    }

    private static void deleteFile(File file) {
        if (file != null && file.exists()) {
            file.delete();
        }
    }

    /**
     * Verifies that a backup file can be decrypted with the given key.
     * Reads and decrypts the first full chunk to validate the GCM auth tag.
     */
    public static boolean verifyBackupKey(String backupFilePath, byte[] encryptionKey) {
        if (backupFilePath == null || encryptionKey == null) return false;
        File f = new File(backupFilePath);
        if (!f.exists() || f.length() < HEADER_SIZE + IV_LENGTH + 4 + 16) return false;

        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] headerBuf = new byte[HEADER_SIZE];
            if (fis.read(headerBuf) != HEADER_SIZE) return false;

            if (!MessageDigest.isEqual(MAGIC, java.util.Arrays.copyOfRange(headerBuf, 0, 4))) {
                return false;
            }

            // Read IV
            byte[] iv = new byte[IV_LENGTH];
            if (fis.read(iv) != IV_LENGTH) return false;

            // Read chunk length
            byte[] lenBuf = new byte[4];
            if (fis.read(lenBuf) != 4) return false;
            int chunkLen = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (chunkLen < 16 || chunkLen > MAX_CHUNK_SIZE) return false;

            // Read the ENTIRE first chunk (including GCM auth tag at the end)
            byte[] chunk = new byte[chunkLen];
            int offset = 0;
            while (offset < chunkLen) {
                int r = fis.read(chunk, offset, chunkLen - offset);
                if (r == -1) return false;
                offset += r;
            }

            // Decrypt full chunk — doFinal() verifies the GCM auth tag
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
                    new GCMParameterSpec(128, iv));
            cipher.doFinal(chunk);

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Lists all .abk backup files in a directory.
     */
    public static File[] listBackups(File backupDir) {
        if (!backupDir.exists() || !backupDir.isDirectory()) return new File[0];
        return backupDir.listFiles((dir, name) ->
                name.startsWith("GhostMessenger_Backup_") && name.endsWith(".abk"));
    }

    /**
     * Deletes a specific backup file.
     */
    public static boolean deleteBackup(String backupFilePath) {
        File f = new File(backupFilePath);
        return f.exists() ? f.delete() : false;
    }

    /**
     * Returns metadata about a backup file without decrypting it.
     */
    public static BackupInfo getBackupInfo(String backupFilePath) {
        File f = new File(backupFilePath);
        if (!f.exists()) return null;

        BackupInfo info = new BackupInfo();
        info.filePath = backupFilePath;
        info.fileName = f.getName();
        info.fileSize = f.length();
        info.lastModified = new Date(f.lastModified());

        try {
            String tsStr = f.getName()
                    .replace("GhostMessenger_Backup_", "")
                    .replace(".abk", "");
            info.timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(tsStr);
        } catch (Exception e) {
            info.timestamp = info.lastModified;
        }

        return info;
    }


    // ==================== REKEYING FOR BACKUP ====================

    /**
     * Copies the database to a temp file and rekeys the copy to the new password.
     * The original database is NOT modified.
     * 
     * @return Path to the rekeyed temp file, or null on failure
     */
    private static File copyAndRekeyDatabase(DxApplication app, byte[] newPassword) {
        File originalDb = new File(app.getFilesDir(), "demo.db");
        if (!originalDb.exists()) {
            Log.w(TAG, "[REKEY] Original database not found: " + originalDb.getAbsolutePath());
            return null;
        }

        // Get the old password from account
        byte[] oldPassword = null;
        if (app.getAccount() != null && app.getAccount().getPassword() != null) {
            oldPassword = app.getAccount().getPassword();
        }
        if (oldPassword == null) {
            Log.w(TAG, "[REKEY] No old password available in account");
            return null;
        }

        // Create temp file in cache directory
        File tempDb = new File(app.getCacheDir(), "backup_rekey_temp_" + System.currentTimeMillis() + ".db");
        
        // Copy the original database to temp file
        try (FileInputStream fis = new FileInputStream(originalDb);
             FileOutputStream fos = new FileOutputStream(tempDb)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
            fos.flush();
        } catch (IOException e) {
            Log.e(TAG, "[REKEY] Failed to copy database: " + e.getMessage(), e);
            tempDb.delete();
            return null;
        }
        Log.d(TAG, "[REKEY] Database copied to: " + tempDb.getAbsolutePath());

        // Open the temp copy with old password and rekey to new password
        SQLiteDatabase tempDbHandle = null;
        try {
            // Load SQLCipher libs if not already loaded
            try {
                net.sqlcipher.database.SQLiteDatabase.loadLibs(app);
            } catch (Throwable t) {
                Log.w(TAG, "[REKEY] Could not load SQLCipher libs (test environment?): " + t.getMessage());
            }

            tempDbHandle = net.sqlcipher.database.SQLiteDatabase.openOrCreateDatabase(
                    tempDb, new String(oldPassword, java.nio.charset.StandardCharsets.UTF_8), null);
            
            // Rekey the temp database directly to the new password
            String passwordStr = new String(newPassword, java.nio.charset.StandardCharsets.UTF_8);
            String escapedPassword = android.database.DatabaseUtils.sqlEscapeString(passwordStr);
            tempDbHandle.rawQuery("PRAGMA rekey = " + escapedPassword + ";", null).close();
            tempDbHandle.close();
            tempDbHandle = null;
            
            Log.d(TAG, "[REKEY] Database copy rekeyed successfully: " + tempDb.getAbsolutePath());
            return tempDb;
        } catch (Throwable e) {
            Log.e(TAG, "[REKEY] Failed to rekey database copy: " + e.getMessage(), e);
            if (tempDbHandle != null) {
                try { tempDbHandle.close(); } catch (Exception ignored) {}
            }
            tempDb.delete();
            return null;
        }
    }

    /**
     * Deletes a temporary database file safely.
     */
    private static void deleteTempDatabase(File tempDb) {
        if (tempDb != null && tempDb.exists()) {
            if (tempDb.delete()) {
                Log.d(TAG, "[REKEY] Temporary database deleted: " + tempDb.getAbsolutePath());
            } else {
                Log.w(TAG, "[REKEY] Failed to delete temporary database: " + tempDb.getAbsolutePath());
            }
        }
    }

    /**
     * Rekeys all encrypted files in the app's files directory.
     * Decrypts files with old password's SHA-256 and re-encrypts with new password's SHA-256.
     * 
     * @return Directory containing rekeyed files, or null on failure
     */
    private static File rekeyEncryptedFiles(DxApplication app, byte[] newPassword) {
        File oldFilesDir = app.getFilesDir();
        File newFilesDir = new File(app.getCacheDir(), "rekeyed_files_" + System.currentTimeMillis());
        
        if (!newFilesDir.exists() && !newFilesDir.mkdirs()) {
            Log.e(TAG, "[REKEY] Failed to create rekeyed files directory");
            return null;
        }

        // Get old and new encryption keys
        byte[] oldKey = null;
        if (app.getAccount() != null && app.getAccount().getPassword() != null) {
            try {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                oldKey = digest.digest(app.getAccount().getPassword());
            } catch (java.security.NoSuchAlgorithmException e) {
                Log.e(TAG, "[REKEY] SHA-256 not available: " + e.getMessage());
                return null;
            }
        }

        if (oldKey == null) {
            Log.e(TAG, "[REKEY] No old password available for file rekeying");
            return null;
        }

        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] newKey = digest.digest(newPassword);

            File[] files = oldFilesDir.listFiles();
            if (files == null) {
                Log.d(TAG, "[REKEY] No files to rekey");
                return newFilesDir;
            }

            for (File file : files) {
                if (file.isDirectory()) continue;
                
                String name = file.getName();
                
                // Skip database files (handled separately)
                if (isDatabaseFile(name)) continue;
                
                // Skip Tor-related directories
                if (name.startsWith("tor") || name.startsWith("app_tor") || name.startsWith("app_data")) continue;
                
                // Skip account_data.xml if it exists
                if (name.equals("account_data.xml")) continue;

                try {
                    // Read encrypted file
                    byte[] encryptedData = readFileBytes(file);
                    if (encryptedData == null || encryptedData.length == 0) {
                        Log.w(TAG, "[REKEY] Skipping empty or unreadable file: " + name);
                        continue;
                    }

                    // Decrypt with old key
                    byte[] decryptedData = FileHelper.decrypt(oldKey, encryptedData);
                    
                    // Re-encrypt with new key
                    byte[] rekeyedData = FileHelper.encrypt(newKey, decryptedData);
                    
                    // Save to new directory with same name
                    File newFile = new File(newFilesDir, name);
                    writeFileBytes(newFile, rekeyedData);
                    
                    Log.d(TAG, "[REKEY] Rekeyed file: " + name);
                } catch (Exception e) {
                    Log.w(TAG, "[REKEY] Failed to rekey file " + name + ": " + e.getMessage());
                    // Continue with other files
                }
            }

            Log.d(TAG, "[REKEY] File rekeying complete. Rekeyed " + newFilesDir.listFiles().length + " files");
            return newFilesDir;

        } catch (Exception e) {
            Log.e(TAG, "[REKEY] File rekeying failed: " + e.getMessage(), e);
            deleteDirectory(newFilesDir);
            return null;
        }
    }

    private static byte[] readFileBytes(File file) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            Log.e(TAG, "Failed to read file: " + e.getMessage());
            return null;
        }
    }

    private static void writeFileBytes(File file, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
            fos.flush();
        }
    }

    private static void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectory(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }

    // ==================== ZIP CREATION ====================

    private static void zipAllData(DxApplication app, File zipFile, ProgressListener listener, File rekeyedDbCopy, File rekeyedFilesDir)
            throws IOException {

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            int[] progress = {10};

            // 1. Database files
            if (listener != null) listener.onProgress(progress[0], "Backing up database...");
            zipDatabase(app, zos, rekeyedDbCopy);
            progress[0] = 30;

            // 2. App files (encrypted attachments, profile images, etc.)
            //    Skip DB files — already handled by zipDatabase() above
            if (listener != null) listener.onProgress(progress[0], "Backing up files...");
            if (rekeyedFilesDir != null) {
                zipDirectory(rekeyedFilesDir, zos, "files/", BackupHelper::isDatabaseFile);
            } else {
                zipDirectory(app.getFilesDir(), zos, "files/", BackupHelper::isDatabaseFile);
            }
            progress[0] = 40;

            // 3. Shared preferences
            if (listener != null) listener.onProgress(progress[0], "Backing up preferences...");
            zipSharedPreferences(app, zos);
            progress[0] = 45;

            // 4. Tor data
            if (listener != null) listener.onProgress(progress[0], "Backing up Tor state...");
            zipTorData(app, zos);
            progress[0] = 48;

            // 5. Account metadata
            if (listener != null) listener.onProgress(progress[0], "Saving account info...");
            zipAccountData(app, zos);
        }
    }

    private static void zipDatabase(DxApplication app, ZipOutputStream zos, File rekeyedDbCopy) throws IOException {
        // Use the rekeyed copy if available, otherwise use the original
        File dbFile = (rekeyedDbCopy != null && rekeyedDbCopy.exists()) ? rekeyedDbCopy : new File(app.getFilesDir(), "demo.db");
        if (dbFile.exists()) {
            addToZip(zos, "files/demo.db", dbFile);
        }
        // Also pick up WAL/journal companions in getFilesDir()
        File filesDir = app.getFilesDir();
        File[] companions = filesDir.listFiles((dir, name) ->
                name.endsWith("-journal") || name.endsWith("-wal") || name.endsWith("-shm"));
        if (companions != null) {
            for (File f : companions) {
                addToZip(zos, "files/" + f.getName(), f);
            }
        }
        // Legacy fallback: also check dataDir/databases/ in case of migration
        File legacyDbDir = new File(app.getApplicationInfo().dataDir, "databases");
        if (legacyDbDir.exists()) {
            for (File f : legacyDbDir.listFiles()) {
                if (f == null || !f.isFile()) continue;
                String name = f.getName();
                if (name.endsWith(".db") || name.endsWith("-journal") ||
                        name.endsWith("-wal") || name.endsWith("-shm")) {
                    addToZip(zos, "databases/" + name, f);
                }
            }
        }
    }

    private static void zipSharedPreferences(DxApplication app, ZipOutputStream zos) throws IOException {
        File prefsDir = new File(app.getApplicationInfo().dataDir, "shared_prefs");
        if (!prefsDir.exists()) return;

        for (File f : prefsDir.listFiles()) {
            if (f != null && f.isFile() && f.getName().endsWith(".xml")) {
                addToZip(zos, "shared_prefs/" + f.getName(), f);
            }
        }
    }

    private static void zipTorData(DxApplication app, ZipOutputStream zos) throws IOException {
        // Use same method as Tor startup code (ServerSocketViaTor line 93)
        File torfilesDir = app.getDir("torfiles", android.content.Context.MODE_PRIVATE);
        
        // Back up everything in torfiles directory (includes hiddenservice/ with keys)
        if (torfilesDir.exists() && torfilesDir.isDirectory()) {
            zipDirectory(torfilesDir, zos, "torfiles/");
        }

        // Also back up app_tor and app_data if they exist
        File dataDir = app.getDataDir();
        String[] otherTorDirs = {"app_tor", "app_data"};
        for (String dirName : otherTorDirs) {
            File torDir = new File(dataDir, dirName);
            if (torDir.exists() && torDir.isDirectory()) {
                zipDirectory(torDir, zos, dirName + "/");
            }
        }
    }

    private static void zipAccountData(DxApplication app, ZipOutputStream zos) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<account>\n");
        sb.append("  <timestamp>").append(System.currentTimeMillis()).append("</timestamp>\n");
        sb.append("  <version>1</version>\n");

        if (app.getAccount() != null) {
            sb.append("  <nickname>")
                    .append(escapeXml(app.getAccount().getNickname()))
                    .append("</nickname>\n");
            // Store password as hex so it survives the backup/restore cycle
            byte[] pw = app.getAccount().getPassword();
            if (pw != null) {
                sb.append("  <passwordHex>").append(Hex.toStringCondensed(pw)).append("</passwordHex>\n");
            }
            String addr = app.getAccount().getAddress();
            if (addr != null) {
                sb.append("  <address>").append(escapeXml(addr)).append("</address>\n");
            }
            String profilePath = app.getAccount().getProfileImagePath();
            if (profilePath != null) {
                sb.append("  <profileImagePath>").append(escapeXml(profilePath)).append("</profileImagePath>\n");
            }
        }
        if (app.getHostname() != null) {
            sb.append("  <hostname>").append(app.getHostname()).append("</hostname>\n");
        }

        sb.append("</account>");

        zos.putNextEntry(new ZipEntry("account_data.xml"));
        zos.write(sb.toString().getBytes());
        zos.closeEntry();
    }

    private static void zipDirectory(File dir, ZipOutputStream zos, String basePath) throws IOException {
        zipDirectory(dir, zos, basePath, null);
    }

    private static void zipDirectory(File dir, ZipOutputStream zos, String basePath,
                                      java.util.function.Predicate<String> skipFilter) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                zipDirectory(f, zos, basePath + f.getName() + "/", skipFilter);
            } else {
                String name = f.getName();
                if (skipFilter == null || !skipFilter.test(name)) {
                    addToZip(zos, basePath + name, f);
                }
            }
        }
    }

    private static boolean isDatabaseFile(String name) {
        return name.endsWith(".db") || name.endsWith("-journal") ||
                name.endsWith("-wal") || name.endsWith("-shm");
    }

    private static void addToZip(ZipOutputStream zos, String entryName, File file) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        entry.setTime(file.lastModified());
        zos.putNextEntry(entry);

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int len;
            while ((len = fis.read(buf)) > 0) {
                zos.write(buf, 0, len);
            }
        }
        zos.closeEntry();
    }


    // ==================== STREAMING ENCRYPTION ====================

    /**
     * Encrypts the ZIP file in chunks using AES/GCM streaming.
     * Writes: [header][IV + chunkLen + encryptedChunk]...
     */
    private static void streamEncryptZip(File zipFile, File outFile, byte[] key,
                                          byte[] checksum, long zipSize, ProgressListener listener)
            throws Exception {

        try (OutputStream fosOut = new BufferedOutputStream(new FileOutputStream(outFile))) {
            // Write header: magic + version + checksum + original size
            writeHeader(fosOut, checksum, zipSize);

            // Stream-encrypt the ZIP content in chunks
            try (InputStream fisIn = new BufferedInputStream(new FileInputStream(zipFile))) {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                byte[] buf = new byte[CHUNK_SIZE];
                int totalRead = 0;

                while (true) {
                    int read = fisIn.read(buf);
                    if (read == -1) break;

                    // Generate fresh IV per chunk
                    byte[] iv = Utils.getSecretBytes(IV_LENGTH);
                    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                            new GCMParameterSpec(128, iv));

                    byte[] encrypted = cipher.doFinal(buf, 0, read);

                    // Write: IV + encryptedLength + encryptedData
                    fosOut.write(iv);
                    fosOut.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(encrypted.length).array());
                    fosOut.write(encrypted);
                    fosOut.flush();

                    totalRead += read;
                    if (listener != null && zipSize > 0) {
                        int pct = 55 + (int) ((double) totalRead / zipSize * 40);
                        listener.onProgress(Math.min(pct, 95), "Encrypting... " + formatSize(totalRead));
                    }
                }

                // Terminator: zero-length chunk
                byte[] iv = Utils.getSecretBytes(IV_LENGTH);
                fosOut.write(iv);
                fosOut.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(0).array());
            }
        }
    }

    /**
     * Decrypts the backup file in chunks and writes the ZIP to temp file.
     */
    private static void streamDecryptBackup(File backupFile, File zipOut, byte[] key,
                                              HeaderInfo header, ProgressListener listener)
            throws Exception {

        try (OutputStream fosOut = new BufferedOutputStream(new FileOutputStream(zipOut))) {
            try (InputStream fisIn = new BufferedInputStream(new FileInputStream(backupFile))) {
                // Skip header
                byte[] skip = new byte[HEADER_SIZE];
                int read = fisIn.read(skip);
                if (read != HEADER_SIZE) {
                    throw new IOException("Header read " + read + " bytes, expected " + HEADER_SIZE);
                }

                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                int totalDecrypted = 0;

                while (true) {
                    // Read IV
                    byte[] iv = new byte[IV_LENGTH];
                    if (fisIn.read(iv) != IV_LENGTH) break;

                    // Read chunk length
                    byte[] lenBuf = new byte[4];
                    if (fisIn.read(lenBuf) != 4) break;
                    int chunkLen = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).getInt();

                    // Terminator check
                    if (chunkLen == 0) break;

                    // Read encrypted chunk
                    byte[] encChunk = new byte[chunkLen];
                    int offset = 0;
                    while (offset < chunkLen) {
                        int r = fisIn.read(encChunk, offset, chunkLen - offset);
                        if (r == -1) throw new IOException("Unexpected end of backup file");
                        offset += r;
                    }

                    // Decrypt
                    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                            new GCMParameterSpec(128, iv));
                    byte[] decrypted = cipher.doFinal(encChunk);
                    fosOut.write(decrypted);
                    fosOut.flush();

                    totalDecrypted += decrypted.length;
                    if (listener != null && header.originalSize > 0) {
                        int pct = 20 + (int) ((double) totalDecrypted / header.originalSize * 35);
                        listener.onProgress(pct, "Decrypting... " + formatSize(totalDecrypted));
                    }
                }
            }
        }
    }


    // ==================== FORMAT DETECTION ====================

    private static boolean checkNewFormat(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] magic = new byte[MAGIC.length];
            int read = fis.read(magic);
            if (read != MAGIC.length) {
                return false;
            }
            return MessageDigest.isEqual(MAGIC, magic);
        }
    }


    // ==================== LEGACY DECRYPT (no header, no checksum) ====================

    private static void streamDecryptLegacyBackup(File backupFile, File zipOut, byte[] key,
                                                      ProgressListener listener) throws Exception {
        try (OutputStream fosOut = new BufferedOutputStream(new FileOutputStream(zipOut));
              InputStream fisIn = new BufferedInputStream(new FileInputStream(backupFile))) {


            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            int totalDecrypted = 0;
            long fileSize = backupFile.length();

            while (true) {
                byte[] iv = new byte[IV_LENGTH];
                if (fisIn.read(iv) != IV_LENGTH) break;

                byte[] lenBuf = new byte[4];
                if (fisIn.read(lenBuf) != 4) break;
                int chunkLen = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).getInt();
                if (chunkLen == 0) break;
                if (chunkLen > MAX_CHUNK_SIZE) throw new IOException("Legacy chunk too large: " + chunkLen);

                byte[] encChunk = new byte[chunkLen];
                int offset = 0;
                while (offset < chunkLen) {
                    int r = fisIn.read(encChunk, offset, chunkLen - offset);
                    if (r == -1) throw new IOException("Unexpected end of backup file");
                    offset += r;
                }

                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(128, iv));
                byte[] decrypted = cipher.doFinal(encChunk);
                fosOut.write(decrypted);
                fosOut.flush();

                totalDecrypted += decrypted.length;
                if (listener != null && fileSize > 0) {
                    int pct = 20 + (int) ((double) totalDecrypted / fileSize * 35);
                    listener.onProgress(pct, "Decrypting (legacy)... " + formatSize(totalDecrypted));
                }
            }
        }
    }

    // ==================== HEADER FORMAT ====================

    private static void writeHeader(OutputStream os, byte[] checksum, long originalSize)
            throws IOException {
        os.write(MAGIC);
        os.write((VERSION >> 8) & 0xFF);
        os.write(VERSION & 0xFF);
        os.write(checksum);
        os.write(ByteBuffer.allocate(8).putLong(originalSize).array());
        os.flush();
    }

    private static HeaderInfo readHeader(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[HEADER_SIZE];
            if (fis.read(header) != HEADER_SIZE) return null;

            // Validate magic
            if (!MessageDigest.isEqual(MAGIC, java.util.Arrays.copyOfRange(header, 0, 4))) {
                return null;
            }

            // Validate version
            int version = ((header[4] & 0xFF) << 8) | (header[5] & 0xFF);
            if (version != VERSION) {
                Log.w(TAG, "Backup version mismatch: expected " + VERSION + " got " + version);
            }

            // Extract checksum and size
            byte[] checksum = java.util.Arrays.copyOfRange(header, 6, 6 + CHECKSUM_LENGTH);
            long originalSize = ByteBuffer.wrap(java.util.Arrays.copyOfRange(header,
                    6 + CHECKSUM_LENGTH, HEADER_SIZE)).getLong(0);

            return new HeaderInfo(checksum, originalSize);
        }
    }


    // ==================== RESTORE EXTRACTION ====================

    private static void extractAndRestore(DxApplication app, File zipFile, ProgressListener listener)
            throws IOException {

        File tempRestoreDir = new File(app.getCacheDir(), "restore_temp_files_" + System.currentTimeMillis());
        tempRestoreDir.mkdirs();

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            long totalEntries = countZipEntries(zipFile);
            long currentEntry = 0;

            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();

                if (name.startsWith("databases/") || name.startsWith("files/")) {
                    String relative = name.startsWith("databases/") ? name.substring("databases/".length()) : name.substring("files/".length());
                    File target = new File(tempRestoreDir, "files/" + relative);
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(target)) {
                        byte[] buf = new byte[BUFFER_SIZE];
                        int len;
                        while ((len = zis.read(buf)) > 0) {
                            fos.write(buf, 0, len);
                        }
                    }
                } else if (name.startsWith("shared_prefs/")) {
                    String relative = name.substring("shared_prefs/".length());
                    File target = new File(tempRestoreDir, "shared_prefs/" + relative);
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(target)) {
                        byte[] buf = new byte[BUFFER_SIZE];
                        int len;
                        while ((len = zis.read(buf)) > 0) {
                            fos.write(buf, 0, len);
                        }
                    }
                } else if (name.startsWith("app_tor/") || name.startsWith("app_data/") || name.startsWith("torfiles/")) {
                    String relative = name;
                    if (name.startsWith("torfiles/")) {
                        relative = "torfiles/" + name.substring("torfiles/".length());
                    } else if (name.startsWith("app_tor/")) {
                        relative = "app_tor/" + name.substring("app_tor/".length());
                    } else if (name.startsWith("app_data/")) {
                        relative = "app_data/" + name.substring("app_data/".length());
                    }
                    File target = new File(tempRestoreDir, relative);
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(target)) {
                        byte[] buf = new byte[BUFFER_SIZE];
                        int len;
                        while ((len = zis.read(buf)) > 0) {
                            fos.write(buf, 0, len);
                        }
                    }
                } else if (name.equals("account_data.xml")) {
                    consumeEntry(zis);
                }

                zis.closeEntry();
                currentEntry++;
                if (listener != null && totalEntries > 0) {
                    int pct = 65 + (int) ((double) currentEntry / totalEntries * 25);
                    listener.onProgress(pct, "Restoring file " + currentEntry + "/" + totalEntries);
                }
            }
        }

        moveTempFilesToFinalLocation(app, tempRestoreDir);
    }

    private static void moveTempFilesToFinalLocation(DxApplication app, File tempRestoreDir) throws IOException {
        File filesDir = new File(tempRestoreDir, "files");
        if (filesDir.exists() && filesDir.isDirectory()) {
            File[] files = filesDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    File target = new File(app.getFilesDir(), file.getName());
                    if (target.exists()) target.delete();
                    file.renameTo(target);
                }
            }
        }

        File sharedPrefsDir = new File(tempRestoreDir, "shared_prefs");
        if (sharedPrefsDir.exists() && sharedPrefsDir.isDirectory()) {
            File[] files = sharedPrefsDir.listFiles();
            if (files != null) {
                File targetDir = new File(app.getApplicationInfo().dataDir, "shared_prefs");
                if (!targetDir.exists()) targetDir.mkdirs();
                for (File file : files) {
                    File target = new File(targetDir, file.getName());
                    if (target.exists()) target.delete();
                    file.renameTo(target);
                }
            }
        }

        File torDir = new File(tempRestoreDir, "torfiles");
        if (torDir.exists() && torDir.isDirectory()) {
            moveTorDirectory(app, torDir, "torfiles");
        }

        File appTorDir = new File(tempRestoreDir, "app_tor");
        if (appTorDir.exists() && appTorDir.isDirectory()) {
            moveTorDirectory(app, appTorDir, "app_tor");
        }

        File appDataDir = new File(tempRestoreDir, "app_data");
        if (appDataDir.exists() && appDataDir.isDirectory()) {
            moveTorDirectory(app, appDataDir, "app_data");
        }
    }

    private static void moveTorDirectory(DxApplication app, File tempDir, String prefix) throws IOException {
        File targetDir;
        if (prefix.equals("torfiles")) {
            targetDir = app.getDir("torfiles", android.content.Context.MODE_PRIVATE);
        } else if (prefix.equals("app_tor")) {
            targetDir = new File(app.getDataDir(), "app_tor");
        } else {
            targetDir = new File(app.getDataDir(), "app_data");
        }

        if (!targetDir.exists()) targetDir.mkdirs();

        File[] files = tempDir.listFiles();
        if (files != null) {
            for (File file : files) {
                File target = new File(targetDir, file.getName());
                if (target.exists()) target.delete();
                file.renameTo(target);
            }
        }
    }

    private static void restoreToPath(DxApplication app, String entryName, String prefix,
                                       File targetBase, ZipInputStream zis) throws IOException {
        String relative = entryName.substring(prefix.length());
        // Path traversal protection: reject entries with ".."
        if (relative.contains("..")) {
            Log.w(TAG, "Path traversal rejected: " + entryName);
            try { byte[] buf = new byte[BUFFER_SIZE]; while (zis.read(buf) > 0) {} } catch (Exception ignored) {}
            return;
        }
        File target = new File(targetBase, relative);
        // Canonical path check: ensure resolved path stays under targetBase
        try {
            if (!target.getCanonicalFile().getPath().startsWith(targetBase.getCanonicalFile().getPath())) {
                Log.w(TAG, "Canonical path escape: " + entryName);
                byte[] buf = new byte[BUFFER_SIZE];
                while (zis.read(buf) > 0) {}
                return;
            }
        } catch (IOException e) {
            Log.w(TAG, "Canonical path check failed: " + entryName);
            byte[] buf = new byte[BUFFER_SIZE];
            while (zis.read(buf) > 0) {}
            return;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (FileOutputStream fos = new FileOutputStream(target)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int len;
            while ((len = zis.read(buf)) > 0) {
                fos.write(buf, 0, len);
            }
        }
        Log.d(TAG, "Restored: " + entryName);
    }

    private static void restoreTorEntry(DxApplication app, String entryName, ZipInputStream zis)
            throws IOException {
        File targetDir;
        String relativePath;

        // Use same method as Tor startup code
        if (entryName.startsWith("torfiles/")) {
            targetDir = app.getDir("torfiles", android.content.Context.MODE_PRIVATE);
            relativePath = entryName.substring("torfiles/".length());
        } else if (entryName.startsWith("app_tor/")) {
            targetDir = new File(app.getDataDir(), "app_tor");
            relativePath = entryName.substring("app_tor/".length());
        } else if (entryName.startsWith("app_data/")) {
            targetDir = new File(app.getDataDir(), "app_data");
            relativePath = entryName.substring("app_data/".length());
        } else {
            targetDir = app.getDataDir();
            relativePath = entryName;
        }
        
        File targetFile = new File(targetDir, relativePath);

        // Path traversal protection
        if (relativePath.contains("..")) {
            Log.w(TAG, "Tor path traversal rejected: " + entryName);
            consumeEntry(zis);
            return;
        }
        try {
            if (!targetFile.getCanonicalFile().getPath().startsWith(targetDir.getCanonicalFile().getPath())) {
                Log.w(TAG, "Tor canonical escape: " + entryName);
                consumeEntry(zis);
                return;
            }
        } catch (IOException e) {
            Log.w(TAG, "Tor canonical check failed: " + entryName);
            consumeEntry(zis);
            return;
        }
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int len;
            while ((len = zis.read(buf)) > 0) {
                fos.write(buf, 0, len);
            }
        }
        Log.d(TAG, "Restored Tor: " + entryName);
    }

    private static void consumeEntry(ZipInputStream zis) throws IOException {
        byte[] buf = new byte[BUFFER_SIZE];
        while (zis.read(buf) > 0) { /* discard */ }
    }

    private static long countZipEntries(File zipFile) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            long count = 0;
            while (zis.getNextEntry() != null) {
                count++;
                zis.closeEntry();
            }
            return count;
        }
    }


    // ==================== DB MANAGEMENT ====================

    /**
     * Closes the current database and clears the app's reference in a synchronized block.
     * Returns true if a DB was actually closed (was open before this call).
     */
    private static boolean closeAndClearDb(DxApplication app) {
        SQLiteDatabase db = app.getDbOrNull();
        boolean wasOpen = (db != null && db.isOpen());
        if (wasOpen) {
            db.close();
        }
        // Clear the reference so subsequent getDb() calls create a fresh connection
        app.setDb(null);
        return wasOpen;
    }

    /**
     * Reopens the database using the account password.
     * @param account  Account to use for DB password (may differ from app.getAccount() during restore)
     */
    private static void reopenDb(DxApplication app, DxAccount account, byte[] rawPassword) throws Exception {
        byte[] password = null;
        if (rawPassword != null) {
            password = rawPassword;
        } else if (account != null) {
            password = account.getPassword();
        } else if (app.getAccount() != null) {
            password = app.getAccount().getPassword();
        }

        if (password == null) {
            throw new Exception("No password available to reopen database");
        }
        
        SQLiteDatabase restored = app.getDb(password);
        if (restored == null || !restored.isOpen()) {
            throw new Exception("Database could not be opened or is not open");
        }
        Log.d(TAG, "Database reopened successfully");
    }

    /**
     * Reads account_data.xml from the ZIP and reconstructs DxAccount.
     * Falls back to the in-memory account reference if ZIP has no account data.
     */
    private static DxAccount loadAccountFromZip(File zipFile, DxAccount fallbackAccount) {
        DxAccount fromZip = null;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("account_data.xml".equals(entry.getName())) {
                    fromZip = parseAccountXml(zis);
                    break;
                }
                zis.closeEntry();
            }
        } catch (Exception e) {
            Log.w(TAG, "loadAccountFromZip: " + e.getMessage());
        }
        DxAccount result = (fromZip != null) ? fromZip : fallbackAccount;
        Log.d(TAG, "loadAccountFromZip: result=" + (result != null ? "present (zip=" + (fromZip != null) + ")" : "null"));
        return result;
    }

    private static DxAccount parseAccountXml(InputStream is) throws Exception {
        byte[] buf = new byte[4096];
        int len = is.read(buf);
        String xml = new String(buf, 0, len, java.nio.charset.StandardCharsets.UTF_8);

        DxAccount acct = new DxAccount();

        // Simple XML parsing (no external dependency)
        String nickname = extractXmlTag(xml, "nickname");
        if (nickname != null) acct.setNickname(nickname);

        String pwHex = extractXmlTag(xml, "passwordHex");
        if (pwHex != null) {
            try { acct.setPassword(Hex.fromStringCondensed(pwHex)); }
            catch (Exception e) { Log.w(TAG, "passwordHex decode: " + e.getMessage()); }
        }

        String address = extractXmlTag(xml, "address");
        if (address != null) acct.setAddress(address);

        String profilePath = extractXmlTag(xml, "profileImagePath");
        if (profilePath != null) acct.setProfileImagePath(profilePath);

        return acct;
    }

    private static String extractXmlTag(String xml, String tag) {
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int start = xml.indexOf(open);
        if (start == -1) return null;
        start += open.length();
        int end = xml.indexOf(close, start);
        if (end == -1) return null;
        return xml.substring(start, end);
    }


    // ==================== UTILITIES ====================

    private static byte[] sha256File(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[BUFFER_SIZE];
            int len;
            while ((len = fis.read(buf)) > 0) {
                md.update(buf, 0, len);
            }
            return md.digest();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static void cleanup(File file) {
        if (file != null && file.exists()) {
            file.delete();
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }


    // ==================== DATA CLASSES ====================

    private static class HeaderInfo {
        final byte[] checksum;
        final long originalSize;

        HeaderInfo(byte[] checksum, long originalSize) {
            this.checksum = checksum;
            this.originalSize = originalSize;
        }
    }

    /**
     * Backup file metadata.
     */
    public static class BackupInfo {
        public String filePath;
        public String fileName;
        public long fileSize;
        public Date timestamp;
        public Date lastModified;

        public String getFormattedSize() {
            return formatSize(fileSize);
        }

        public String getFormattedDate() {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            return sdf.format(timestamp);
        }
    }
}
