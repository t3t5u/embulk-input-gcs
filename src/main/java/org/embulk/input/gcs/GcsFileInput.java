package org.embulk.input.gcs;

import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.embulk.config.ConfigException;
import org.embulk.config.TaskReport;
import org.embulk.spi.Exec;
import org.embulk.spi.TransactionalFileInput;
import org.embulk.util.file.InputStreamFileInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.embulk.input.gcs.GcsFileInputPlugin.CONFIG_MAPPER_FACTORY;

public class GcsFileInput
        extends InputStreamFileInput
        implements TransactionalFileInput
{
    private static final Logger LOG = LoggerFactory.getLogger(org.embulk.input.gcs.GcsFileInput.class);

    GcsFileInput(PluginTask task, int taskIndex)
    {
        super(Exec.getBufferAllocator(), new SingleFileProvider(task, taskIndex));
    }

    public void abort()
    {
    }

    public TaskReport commit()
    {
        return CONFIG_MAPPER_FACTORY.newTaskReport();
    }

    @Override
    public void close()
    {
    }

    /**
     * Lists GCS filenames filtered by prefix.
     *
     * The resulting list does not include the file that's size == 0.
     */
    static FileList listFiles(PluginTask task)
    {
        Storage client = AuthUtils.newClient(task);
        String bucket = task.getBucket();

        // @see https://cloud.google.com/storage/docs/json_api/v1/buckets/get
        if (LOG.isDebugEnabled()) {
            printBucketInfo(client, bucket);
        }

        String prefix = task.getPathPrefix().orElse("");
        String lastKey = task.getLastPath().isPresent() ? base64Encode(task.getLastPath().get()) : "";
        FileList.Builder builder = new FileList.Builder(task);

        try {
            // @see https://cloud.google.com/storage/docs/json_api/v1/objects/list
            Page<Blob> blobs = client.list(bucket, Storage.BlobListOption.prefix(prefix), Storage.BlobListOption.pageToken(lastKey));
            if (task.getStopWhenFileNotFound() && !fileExists(blobs)) {
                throw new ConfigException("No file is found. \"stop_when_file_not_found\" option is \"true\".");
            }
            for (Blob blob : blobs.iterateAll()) {
                if (blob.getSize() > 0) {
                    builder.add(blob.getName(), blob.getSize());
                }
                LOG.debug("filename: {}", blob.getName());
                LOG.debug("updated: {}", blob.getUpdateTime());
            }
        }
        catch (ConfigException e) {
            throw new ConfigException(e.getMessage());
        }
        catch (RuntimeException e) {
            if ((e instanceof StorageException) && ((StorageException) e).getCode() == 400) {
                throw new ConfigException(String.format("Files listing failed: bucket:%s, prefix:%s, last_path:%s", bucket, prefix, lastKey), e);
            }

            LOG.warn(String.format("Could not get file list from bucket:%s", bucket));
            LOG.warn(e.getMessage());
        }
        return builder.build();
    }

    // String nextToken = base64Encode(0x0a + ASCII character according to utf8EncodeLength position+ filePath);
    static String base64Encode(String path)
    {
        byte[] encoding;
        byte[] utf8 = path.getBytes(StandardCharsets.UTF_8);
        LOG.debug("path string: {} ,path length:{} \" + ", path, utf8.length);

        int utf8EncodeLength = utf8.length;
        if (utf8EncodeLength >= 128) {
            throw new ConfigException(String.format("last_path '%s' is too long to encode. Please try to reduce its length", path));
        }

        encoding = new byte[utf8.length + 2];
        encoding[0] = 0x0a;

        // for example: 60 -> '<'
        char temp = (char) utf8EncodeLength;
        encoding[1] = (byte) temp;
        System.arraycopy(utf8, 0, encoding, 2, utf8.length);

        final String s = Base64.getEncoder().encodeToString(encoding);
        LOG.debug("last_path(base64 encoded): {}", s);
        return s;
    }

    private static void printBucketInfo(Storage client, String bucket)
    {
        // get Bucket
        Storage.BucketGetOption fields = Storage.BucketGetOption.fields(
                Storage.BucketField.LOCATION,
                Storage.BucketField.TIME_CREATED,
                Storage.BucketField.OWNER
        );
        com.google.cloud.storage.Bucket bk = client.get(bucket, fields);
        LOG.debug("bucket name: {}", bk.getName());
        LOG.debug("bucket location: {}", bk.getLocation());
        LOG.debug("bucket timeCreated: {}", bk.getCreateTime());
        LOG.debug("bucket owner: {}", bk.getOwner());
    }

    private static boolean fileExists(Page<Blob> blobs)
    {
        for (Blob blob : blobs.iterateAll()) {
            if (!blob.getName().endsWith("/")) {
                return true;
            }
        }
        return false;
    }
}
