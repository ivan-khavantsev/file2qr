import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.qrcode.QRCodeMultiReader;
import org.apache.commons.io.FilenameUtils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.CRC32;

public class Main {

    public static int QR_CODES_PER_ROW = 4; // Количество кодов строку
    public static int QR_CODES_PER_COL = 6; // Количество кодов на колонку
    public static int QR_CODES_PER_PAGE = QR_CODES_PER_ROW * QR_CODES_PER_COL; // Количество кодов на странице
    public static int PAGE_MARGIN = 300; // Отступ на странице
    public static int PAGE_WIDTH = 2490; // Отступ на странице
    public static int PAGE_HEIGHT = 3510; // Отступ на странице

    public static int QR_CODES_SIZE = 480; // Размер QR кода в пикселях
    public static int QR_CODES_SPACE = 10; // Расстояние между QR кодами в пикселях
    public static int DATA_PART_SIZE = 939; // Размер данных одного кода


    public static BufferedImage createQRFromData(byte[] data, int partNumber, long crc32) {
        try {
            byte[] signature = "Q2F".getBytes();
            ByteBuffer byteBuffer = ByteBuffer.allocate(signature.length + 8 + 4 + data.length);
            byteBuffer.put(signature);
            byteBuffer.putLong(crc32);
            byteBuffer.putInt(partNumber);
            byteBuffer.put(data);

            String dataString = Base64.getEncoder().encodeToString(byteBuffer.array());

            Map<EncodeHintType, Object> encodeHints = new HashMap<>();
            encodeHints.put(EncodeHintType.MARGIN, 0); // Чтобы при генерации кода не добавлялись белые рамки вокруг кода и код занимал всю указаную область

            BitMatrix matrix = new MultiFormatWriter().encode(
                    dataString,
                    BarcodeFormat.QR_CODE, QR_CODES_SIZE, QR_CODES_SIZE, encodeHints);

            BufferedImage barCodeBufferedImage = MatrixToImageWriter.toBufferedImage(matrix);
            return barCodeBufferedImage;
        } catch (Throwable throwable) {
            return null;
        }
    }


    public static BufferedImage createPage(FileInputStream fileInputStream, int pageNumber, long crc32) throws IOException {
        BufferedImage image = new BufferedImage(PAGE_WIDTH, PAGE_HEIGHT, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g1 = image.createGraphics();
        g1.setColor(Color.WHITE);
        g1.fillRect(0, 0, image.getWidth(), image.getHeight());
        g1.dispose();

        Graphics graphics = image.getGraphics();

        for (int i = 0; i < QR_CODES_PER_PAGE; i++) {
            int x = ((int) Math.floor(i % QR_CODES_PER_ROW)) * (QR_CODES_SIZE + QR_CODES_SPACE) + PAGE_MARGIN;
            int y = ((int) Math.floor(i / QR_CODES_PER_ROW)) * (QR_CODES_SIZE + QR_CODES_SPACE) + PAGE_MARGIN;

            byte[] bytes = fileInputStream.readNBytes(DATA_PART_SIZE);
            if (bytes.length == 0) break;

            BufferedImage qrCode = createQRFromData(bytes, (pageNumber * QR_CODES_PER_PAGE) + i, crc32);
            graphics.drawImage(qrCode, x, y, null);
        }

        return image;
    }

    public static long getCRC32(String filename) throws IOException {
        File file = new File(filename);
        FileInputStream fileInputStream = new FileInputStream(file);
        CRC32 crc = new CRC32();
        crc.update(fileInputStream.readAllBytes());
        return crc.getValue();
    }


    public static void file2Images(String filename) throws IOException {
        File file = new File(filename);
        long crc32 = getCRC32(filename);
        FileInputStream fileInputStream = new FileInputStream(file);
        int parts = (int) Math.ceil(file.length() / (double) DATA_PART_SIZE);
        int pages = (int) Math.ceil((double) parts / QR_CODES_PER_PAGE);

        String folderToSavePath = file.getAbsolutePath() + "-QR/";
        File folderToSavePathFile = new File(folderToSavePath);
        if (!folderToSavePathFile.exists()) {
            folderToSavePathFile.mkdir();
        }

        for (int i = 0; i < pages; i++) {
            BufferedImage page = createPage(fileInputStream, i, crc32);
            ImageIO.write(page, "png", new File(folderToSavePath + file.getName() + "." + (i) + ".png"));
        }
    }

    public static void images2File(String path) {
        File pathFile = new File(path);
        File[] files = pathFile.listFiles();
        QRCodeMultiReader qrCodeMultiReader = new QRCodeMultiReader();
        String filename = "" + System.currentTimeMillis();
        for (File file : files) {
            try {
                String extension = FilenameUtils.getExtension(file.getName());
                if (!("png".equals(extension))) continue;
                InputStream barCodeInputStream = new FileInputStream(file);
                BufferedImage barCodeBufferedImage = ImageIO.read(barCodeInputStream);
                LuminanceSource source = new BufferedImageLuminanceSource(barCodeBufferedImage);

                BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

                Map<DecodeHintType, Object> decodeHints = new HashMap<>();
                decodeHints.put(DecodeHintType.TRY_HARDER, "5");

                Result[] results = qrCodeMultiReader.decodeMultiple(bitmap, decodeHints);
                for (Result result : results) {
                    byte[] bytes = Base64.getDecoder().decode(result.getText());
                    ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes);
                    DataInputStream dataInputStream = new DataInputStream(byteArrayInputStream);
                    byte[] signature = dataInputStream.readNBytes(3);
                    long crc32 = dataInputStream.readLong();
                    int partNumber = dataInputStream.readInt();

                    byte[] dataToWrite = new byte[bytes.length - 15];
                    dataInputStream.read(dataToWrite);

                    new File(pathFile.getAbsolutePath() + "/parts/").mkdirs();

                    File writeFile = new File(pathFile.getAbsolutePath() + "/parts/" + Long.toHexString(crc32).toLowerCase() + "." + String.format("%03d", (partNumber + 1)));
                    FileOutputStream fileOutputStream = new FileOutputStream(writeFile);
                    fileOutputStream.write(dataToWrite);
                    fileOutputStream.flush();
                    fileOutputStream.close();
                }
            } catch (NotFoundException | IOException exception) {
                System.out.println(exception);
                continue;
            }
        }


    }

    public static void main(String[] args) {

        /*
                Принцип такой. Берём файл. Читаем из него порцию данных. Делаем из порции QR код с пометкой хэша файла и номера части.
                В первую порцию можно так же записать название файла. А в последнюю часть можно записать флаг, что это последний блок.
                Все коды размещаем на листе формата A4. Если данных больше, то сохраняем этот и создаём ещё один файл картинки.

                Данные перед впихиванием в qr код можно паковать. хотя, лучше это предоставить пользователю.
                ---
                При чтении берём все коды из всех картинок в папке и для каждого создаём файл формата <hash>.<part> (001..)
                Эти файлы содержат просто данные, коотрые можно склеить любым тотал коммандером. Но в программу тоже можно внести эту функцию.
                Когда итоговый файл будет собираться с помощью этой программы, то будет проверяться хеш.

                В QR код версии 25 на уровне L влезает 10208 бит. Если данные закодировать в Base64, то в такой код влезет 954 байта

                Листа А4 11.7x8.3 дюймов то есть при DPI 300 это 3510x2490 пикселей.
                Возьмём поля по 300 пикселей с каждой стороны, то есть итоговое поле будет 2910 х 1890

                В первый блок поместим хедер содержащий количество частей, название файла
                При считывании первого qr мы записываем файл <hash>.crc в таком формате:
                filename=archive.7z
                size=41250
                crc32=783e30f1
                В таком случае файл можно собрать с помощью total commander

             */

        try {
            if (args.length > 0) {
                if ("-e".equals(args[0])) {
                    file2Images(args[1]);
                    System.exit(0);
                }
                if ("-d".equals(args[0])) {
                    images2File(args[1]);
                    System.exit(0);
                }
            }
            System.out.println("No parameters");
            System.exit(0);


        } catch (Throwable throwable) {
            System.out.println(throwable);
        }
    }
}
