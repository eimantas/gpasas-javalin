package gpasas;

import com.google.zxing.*;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Main {
    private static final byte[] indexHtml;
    private static final byte[] favicon;
    private static final PKPassTemplateInMemory passTemplate;
    private static final PKSigningInformation pkSigningInformation;

    static {
        try {
            // Change these values
            String certificatePath = "/cert/my.p12";
            String certificatePassword = "password123";

            indexHtml = getResource("/index.html").readAllBytes();
            favicon = getResource("/favicon.ico").readAllBytes();

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
        Javalin app = Javalin.create().start("127.0.0.1", 8080);

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
                ctx.contentType("text/plain; charset=utf-8");
                ctx.result("Atsipra??ome, ka??kas ??vyko ne taip. Patikrinkite ar ??k??l??te teising?? fail??. iOS ??renginiuose rekomenduojame naudoti Safari nar??ykl??.");
            }
        });

        app.get("/favicon.ico", ctx -> {
            ctx.contentType("image/x-icon");
            ctx.result(favicon);
        });
    }

    public static PdfData ExtractPdfData(byte[] request) throws IOException {
        PdfData pdfData = new PdfData();

        try (PDDocument document = PDDocument.load(request)) {
            List<BufferedImage> images = new ExtractImagesUseCase(document).execute();

            for (BufferedImage image : images) {
                try {
                    LuminanceSource source = new BufferedImageLuminanceSource(image);
                    BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

                    Map<DecodeHintType, Boolean> hintMap = new HashMap<>();
                    hintMap.put(DecodeHintType.PURE_BARCODE, Boolean.TRUE);

                    pdfData.qrText = new MultiFormatReader().decode(bitmap, hintMap).getText();
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

                return pdfData;
            }

            throw new RuntimeException("No QR image found");
        }
    }

    public static byte[] MakePkPass(PdfData pdfData) throws PKSigningException {
        PKPass pass = PKPass.builder()
                .pass(
                        PKGenericPass.builder()
                                .passType(PKPassType.PKGenericPass)
                                .primaryFieldBuilder(
                                        PKField.builder()
                                                .key("fullName")
                                                .label("Vardas ir pavard??")
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
                                                .label("I??davimo data")
                                                .value(pdfData.validFrom)
                                )
                                .secondaryFieldBuilder(
                                        PKField.builder()
                                                .key("expirationDate")
                                                .label("Galioja iki")
                                                .value(pdfData.validTill)
                                )
                                .backFieldBuilder(
                                        PKField.builder()
                                                .key("url")
                                                .label("Svetain??")
                                                .value("https://www.gpasas.lt")
                                )
                                .backFieldBuilder(
                                        PKField.builder()
                                                .key("general-info")
                                                .label("Informacija Lietuvos gyventojams")
                                                .value("+370 5 236 45 77")
                                )
                                .backFieldBuilder(
                                        PKField.builder()
                                                .key("work-time")
                                                .label("Darbo laikas")
                                                .value("I???IV 8:00???17:00\nV 8:00???16:00")
                                )
                                .backFieldBuilder(
                                        PKField.builder()
                                                .key("vaccination-url")
                                                .label("Registracija vakcinai")
                                                .value("https://koronastop.lt/")
                                )
                                .backFieldBuilder(
                                        PKField.builder()
                                                .key("mobi-url")
                                                .label("GP telefono pinigin??je")
                                                .value("https://gpasas.mobi/")
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
                .organizationName("V?? Registr?? Centras")
                .logoText("Galimybi?? pasas")
                .description("Galimybi?? pasas")
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
