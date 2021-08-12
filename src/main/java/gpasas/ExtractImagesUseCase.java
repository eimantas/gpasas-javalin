package gpasas;

import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

// https://stackoverflow.com/questions/45567173/extract-multiple-embedded-images-from-a-single-pdf-page-using-pdfbox
public class ExtractImagesUseCase extends PDFStreamEngine {
    private final PDDocument document;
    private BufferedImage image;

    public ExtractImagesUseCase(PDDocument document) {
        this.document = document;
    }

    public BufferedImage execute() {
        try {
            for (PDPage page : document.getPages()) {
                processPage(page);
            }

            return this.image;
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
        String operation = operator.getName();

        if ("Do".equals(operation)) {
            COSName objectName = (COSName) operands.get(0);
            PDXObject pdxObject = getResources().getXObject(objectName);

            if (pdxObject instanceof PDImageXObject) {
                PDImageXObject image = (PDImageXObject) pdxObject;

                this.image = image.getImage();
            } else if (pdxObject instanceof PDFormXObject) {
                PDFormXObject form = (PDFormXObject) pdxObject;
                showForm(form);
            }
        } else {
            super.processOperator(operator, operands);
        }
    }
}
