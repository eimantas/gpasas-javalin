package gpasas;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import de.brendamour.jpasskit.PKBarcode;
import de.brendamour.jpasskit.PKField;
import de.brendamour.jpasskit.PKPass;
import de.brendamour.jpasskit.enums.PKBarcodeFormat;
import de.brendamour.jpasskit.enums.PKPassType;
import de.brendamour.jpasskit.passes.PKGenericPass;
import de.brendamour.jpasskit.signing.*;
import gpasas.model.PdfData;
import io.javalin.Javalin;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class Main {
    private static final byte[] indexHtml;
    private static final PKPassTemplateInMemory passTemplate;
    private static final PKSigningInformation pkSigningInformation;

    static {
        try {
            // Change these values
            String certificatePath = "/cert/my.p12";
            String certificatePassword = "password123";

            indexHtml = getResource("/index.html").readAllBytes();

            passTemplate = new PKPassTemplateInMemory();
            passTemplate.addFile("icon.png", getResource("/template/icon.png"));
            passTemplate.addFile("icon@2x.png", getResource("/template/icon@2x.png"));
            passTemplate.addFile("icon@3x.png", getResource("/template/icon@3x.png"));
            passTemplate.addFile("logo.png", getResource("/template/logo.png"));
            passTemplate.addFile("logo@2x.png", getResource("/template/logo@2x.png"));
            passTemplate.addFile("logo@3x.png", getResource("/template/logo@3x.png"));

            pkSigningInformation = new PKSigningInformationUtil()
                    .loadSigningInformationFromPKCS12AndIntermediateCertificate(
                            getResource(certificatePath),
                            certificatePassword,
                            getResource("/cert/AppleWWDRCA.cer")
                    );
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        Javalin app = Javalin.create().start(8080);

        app.get("/", ctx -> {
            ctx.contentType("text/html");
            ctx.result(indexHtml);
        });

        app.post("/upload", ctx -> {
            try {
                int sizeLimit = 128 * 1024; // 128kB

                byte[] pdfFile = ctx.uploadedFile("gpass").getContent().readNBytes(sizeLimit);

                if (pdfFile.length >= sizeLimit) {
                    throw new RuntimeException("Uploaded file is too big");
                }

                PdfData pdfData = ExtractPdfData(pdfFile);
                byte[] pkPass = MakePkPass(pdfData);

                ctx.contentType("application/vnd.apple.pkpass");
                ctx.header("Content-Disposition", "attachment; filename=\"gpasas.pkpass\"");
                ctx.result(pkPass);
            } catch (Exception e) {
                ctx.status(500);
                ctx.contentType("text/plain");
                ctx.result("Oops, something went wrong. Please check that you have uploaded correct file.");
            }
        });
    }

    public static PdfData ExtractPdfData(byte[] request) throws IOException {
        PdfData pdfData = new PdfData();

        try (PDDocument document = PDDocument.load(request)) {
            BufferedImage img = new ExtractImagesUseCase(document).execute();

            if (img == null) {
                throw new RuntimeException("No QR image found");
            }

            try {
                LuminanceSource source = new BufferedImageLuminanceSource(img);
                BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

                pdfData.qrText = new MultiFormatReader().decode(bitmap).getText();
            } catch (NotFoundException e) {
                throw new RuntimeException("There is no QR code in the image");
            }

            try {
                String[] textLines = new PDFTextStripper().getText(document)
                        .replace("\r", "")
                        .split("\n");

                pdfData.fullName = textLines[2];
                pdfData.dateOfBirth = textLines[4];
                pdfData.validFrom = textLines[6];
                pdfData.validTill = textLines[8];
                pdfData.validTillInstant = LocalDateTime
                        .parse(pdfData.validTill, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                        .toInstant(ZoneOffset.ofHours(2));
            }
            catch (Exception e) {
                throw new RuntimeException("Unexpected text data in PDF file");
            }
        }

        return pdfData;
    }

    public static byte[] MakePkPass(PdfData pdfData) throws PKSigningException {
        PKPass pass = PKPass.builder()
                .pass(
                        PKGenericPass.builder()
                                .passType(PKPassType.PKGenericPass)
                                .primaryFieldBuilder(
                                        PKField.builder()
                                                .key("fullName")
                                                .label("Vardas, pavardė")
                                                .value(pdfData.fullName)
                                )
                                .secondaryFieldBuilder(
                                        PKField.builder()
                                                .key("birthYear")
                                                .label("Gimimo metai")
                                                .value(pdfData.dateOfBirth)
                                )
                                .secondaryFieldBuilder(
                                        PKField.builder()
                                                .key("issueDate")
                                                .label("Išdavimo data")
                                                .value(pdfData.validFrom)
                                )
                                .secondaryFieldBuilder(
                                        PKField.builder()
                                                .key("expirationDate")
                                                .label("Galioja iki")
                                                .value(pdfData.validTill)
                                )
                )
                .barcodeBuilder(
                        PKBarcode.builder()
                                .format(PKBarcodeFormat.PKBarcodeFormatQR)
                                .message(pdfData.qrText)
                                .messageEncoding(StandardCharsets.UTF_8)
                )
                .formatVersion(1)
                .passTypeIdentifier("pass.software.stork.gpass")
                .serialNumber(UUID.randomUUID().toString())
                .teamIdentifier("355U245N96")
                .organizationName("VĮ Registrų Centras")
                .logoText("Galimybių pasas")
                .description("Galimybių pasas")
                .backgroundColor(Color.WHITE)
                .foregroundColor(Color.BLACK)
                .sharingProhibited(true)
                .expirationDate(pdfData.validTillInstant)
                .build();

        return new PKFileBasedSigningUtil()
                .createSignedAndZippedPkPassArchive(pass, passTemplate, pkSigningInformation);
    }

    private static InputStream getResource(String resourcePath) {
        return Main.class.getResourceAsStream(resourcePath);
    }
}
