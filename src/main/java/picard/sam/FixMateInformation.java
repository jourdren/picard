/*
 * The MIT License
 *
 * Copyright (c) 2009 - 2016 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package picard.sam;

import htsjdk.samtools.BAMRecordCodec;
import htsjdk.samtools.MergingSamRecordIterator;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMFileHeader.SortOrder;
import htsjdk.samtools.SAMFileWriter;
import htsjdk.samtools.SAMFileWriterFactory;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMRecordQueryNameComparator;
import htsjdk.samtools.SamFileHeaderMerger;
import htsjdk.samtools.SamPairUtil;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.SamReaderFactory;
import htsjdk.samtools.util.CloserUtil;
import htsjdk.samtools.util.IOUtil;
import htsjdk.samtools.util.Log;
import htsjdk.samtools.util.PeekableIterator;
import htsjdk.samtools.util.ProgressLogger;
import htsjdk.samtools.util.RuntimeIOException;
import htsjdk.samtools.util.SortingCollection;
import org.broadinstitute.barclay.help.DocumentedFeature;
import picard.PicardException;
import picard.cmdline.CommandLineProgram;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.barclay.argparser.Argument;
import picard.cmdline.StandardOptionDefinitions;
import picard.cmdline.programgroups.ReadDataManipulationProgramGroup;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * <h3>Summary</h3>
 * This tool ensures that all mate-pair information is in sync between each read
 * and its mate pair.  If no {@link #OUTPUT} file is supplied then the output is written to a temporary file
 * and then copied over the {@link #INPUT} file (with the original placed in a .old file.)
 * Reads marked with the secondary alignment flag are written to the output file unchanged.
 * However, <b>supplementary</b> reads are corrected so that they point to the primary,
 * non-supplemental mate record.
 *
 * <h3>Usage example:</h3>
 * <pre>
 * java -jar picard.jar FixMateInformation \
 *       I=input.bam \
 *       O=fixed_mate.bam \
 *       ADD_MATE_CIGAR=true
 * </pre>
 * <h3>Caveats</h3>
 * The program should run with fairly limited memory unless there are many mate pairs that are missing or far apart
 * from each other in the file, as it keeps track of the unmatched mates
 *
 * @author Tim Fennell
 */
@CommandLineProgramProperties(
        summary = FixMateInformation.USAGE_SUMMARY + FixMateInformation.USAGE_DETAILS,
        oneLineSummary = FixMateInformation.USAGE_SUMMARY,
        programGroup = ReadDataManipulationProgramGroup.class)
@DocumentedFeature
public class FixMateInformation extends CommandLineProgram {
    static final String USAGE_SUMMARY = "Verify mate-pair information between mates and fix if needed.";
    static final String USAGE_DETAILS = "This tool ensures that all mate-pair information is in sync between each read " +
            "and its mate pair.  If no OUTPUT file is supplied then the output is written to a temporary file " +
            "and then copied over the INPUT file (with the original placed in a .old file.)  Reads marked with the secondary alignment flag are written " +
            "to the output file unchanged. However <b>supplementary</b> reads are corrected so that they point to the primary, " +
            "non-supplemental mate record.\n" +
            "\n" +
            "<h3>Usage example</h3>\n" +
            "\n" +
            "java -jar picard.jar FixMateInformation \\\n" +
            "      I=input.bam \\\n " +
            "      O=fixed_mate.bam \\\n" +
            "      ADD_MATE_CIGAR=true\n" +
            "\n" +
            "<h3>Caveats</h3>\n"+
            "The program should run with fairly limited memory unless there are many mate pairs that are missing or far apart " +
            "from each other in the file, as it keeps track of the unmatched mates.";
    @Argument(shortName = StandardOptionDefinitions.INPUT_SHORT_NAME, doc = "The SAM/BAM/CRAM input files to check and fix. Multiple files will be " +
            "merged and sorted.")
    public List<File> INPUT;

    @Argument(shortName = StandardOptionDefinitions.OUTPUT_SHORT_NAME, optional = true,
            doc = "The output file to write to. If no output file is supplied, the input file is overwritten (only available with single input file).")
    public File OUTPUT;

    @Argument(shortName = StandardOptionDefinitions.SORT_ORDER_SHORT_NAME, optional = true,
            doc = "Optional sort order if the OUTPUT file should be sorted differently than the INPUT file.")
    public SortOrder SORT_ORDER;

    @Argument(doc = "If true, assume that the input file is queryname sorted, even if the header says otherwise.",
            shortName = StandardOptionDefinitions.ASSUME_SORTED_SHORT_NAME)
    public boolean ASSUME_SORTED = false;

    @Argument(shortName = "MC", optional = true, doc = "Adds the mate CIGAR tag (MC) if true, does not if false.")
    public Boolean ADD_MATE_CIGAR = true;

    @Argument(doc = "If true, ignore missing mates, otherwise will throw an exception when missing mates are found.", optional = true)
    public Boolean IGNORE_MISSING_MATES = true;

    private static final Log log = Log.getInstance(FixMateInformation.class);

    protected SAMFileWriter out;

    protected int doWork() {
        // Open up the input
        boolean allQueryNameSorted = true;
        final List<SamReader> readers = new ArrayList<>();
        for (final File f : INPUT) {
            IOUtil.assertFileIsReadable(f);
            final SamReader reader = SamReaderFactory.makeDefault().referenceSequence(REFERENCE_SEQUENCE).open(f);
            readers.add(reader);
            if (reader.getFileHeader().getSortOrder() != SortOrder.queryname) allQueryNameSorted = false;
        }

        // Decide where to write the fixed file - into the specified output file
        // or into a temporary file that will overwrite the INPUT file eventually
        if (OUTPUT != null) OUTPUT = OUTPUT.getAbsoluteFile();
        final boolean differentOutputSpecified = OUTPUT != null;

        if (differentOutputSpecified) {
            IOUtil.assertFileIsWritable(OUTPUT);
        } else if (INPUT.size() != 1) {
            throw new PicardException("Must specify either an explicit OUTPUT file or a single INPUT file to be overridden.");
        } else {
            final File soleInput = INPUT.get(0).getAbsoluteFile();
            final File dir = soleInput.getParentFile().getAbsoluteFile();
            final String extension = parseExtension(soleInput);
            try {
                IOUtil.assertFileIsWritable(soleInput);
                IOUtil.assertDirectoryIsWritable(dir);
                OUTPUT = File.createTempFile(soleInput.getName() + ".being_fixed.", extension, dir);
            } catch (final IOException ioe) {
                throw new RuntimeIOException("Could not create tmp file in " + dir.getAbsolutePath());
            }
        }

        // Get the input records merged and sorted by query name as needed
        final PeekableIterator<SAMRecord> iterator;
        final SAMFileHeader header;

        {
            // Deal with merging if necessary
            final Iterator<SAMRecord> tmp;
            if (INPUT.size() > 1) {
                final List<SAMFileHeader> headers = new ArrayList<>(readers.size());
                for (final SamReader reader : readers) {
                    headers.add(reader.getFileHeader());
                }
                final SortOrder sortOrder = (allQueryNameSorted ? SortOrder.queryname : SortOrder.unsorted);
                final SamFileHeaderMerger merger = new SamFileHeaderMerger(sortOrder, headers, false);
                tmp = new MergingSamRecordIterator(merger, readers, false);
                header = merger.getMergedHeader();
            } else {
                tmp = readers.get(0).iterator();
                header = readers.get(0).getFileHeader();
            }

            // And now deal with re-sorting if necessary
            if (ASSUME_SORTED || allQueryNameSorted) {
                iterator = new SamPairUtil.SetMateInfoIterator(new PeekableIterator<>(tmp), ADD_MATE_CIGAR, IGNORE_MISSING_MATES);
            } else {
                log.info("Sorting input into queryname order.");
                final SortingCollection<SAMRecord> sorter = SortingCollection.newInstance(SAMRecord.class,
                        new BAMRecordCodec(header),
                        new SAMRecordQueryNameComparator(),
                        MAX_RECORDS_IN_RAM,
                        TMP_DIR);
                while (tmp.hasNext()) {
                    sorter.add(tmp.next());

                }

                iterator = new SamPairUtil.SetMateInfoIterator(new PeekableIterator<SAMRecord>(sorter.iterator()) {
                    @Override
                    public void close() {
                        super.close();
                        sorter.cleanup();
                    }
                }, ADD_MATE_CIGAR, IGNORE_MISSING_MATES);
                log.info("Sorting by queryname complete.");
            }
            // Deal with the various sorting complications
            final SortOrder outputSortOrder = SORT_ORDER == null ? readers.get(0).getFileHeader().getSortOrder() : SORT_ORDER;
            log.info("Output will be sorted by " + outputSortOrder);
            header.setSortOrder(outputSortOrder);
        }

        if (CREATE_INDEX && header.getSortOrder() != SortOrder.coordinate) {
            throw new PicardException("Can't CREATE_INDEX unless sort order is coordinate");
        }

        createSamFileWriter(header);

        log.info("Traversing query name sorted records and fixing up mate pair information.");
        final ProgressLogger progress = new ProgressLogger(log);
        while (iterator.hasNext()) {
            final SAMRecord record = iterator.next();
            out.addAlignment(record);
            progress.record(record);
        }
        iterator.close();

        if (header.getSortOrder() == SortOrder.queryname) {
            log.info("Closing output file.");
        } else {
            log.info("Finished processing reads; re-sorting output file.");
        }
        closeWriter();

        // Lastly if we're fixing in place, swap the files
        if (!differentOutputSpecified) {
            log.info("Replacing input file with fixed file.");

            final File soleInput = INPUT.get(0).getAbsoluteFile();
            final File old = new File(soleInput.getParentFile(), soleInput.getName() + ".old");
            if (!old.exists() && soleInput.renameTo(old)) {
                if (OUTPUT.renameTo(soleInput)) {

                    if (!old.delete()) {
                        log.warn("Could not delete old file: " + old.getAbsolutePath());
                        return 1;
                    }

                    if (CREATE_INDEX) {
                        final File newIndex = new File(OUTPUT.getParent(),
                                OUTPUT.getName().substring(0, OUTPUT.getName().length() - 4) + ".bai");
                        final File oldIndex = new File(soleInput.getParent(),
                                soleInput.getName().substring(0, soleInput.getName().length() - 4) + ".bai");

                        if (!newIndex.renameTo(oldIndex)) {
                            log.warn("Could not overwrite index file: " + oldIndex.getAbsolutePath());
                        }
                    }

                } else {
                    log.error("Could not move new file to " + soleInput.getAbsolutePath());
                    log.error("Input file preserved as: " + old.getAbsolutePath());
                    log.error("New file preserved as: " + OUTPUT.getAbsolutePath());
                    return 1;
                }
            } else {
                log.error("Could not move input file out of the way: " + soleInput.getAbsolutePath());

                if (!OUTPUT.delete()) {
                    log.error("Could not delete temporary file: " + OUTPUT.getAbsolutePath());
                }

                return 1;
            }

        }

        CloserUtil.close(readers);
        return 0;
    }

    private String parseExtension(final File input) {
        String[] splitted = input.getName().split("\\.");
        return splitted[splitted.length - 1];
    }

    protected void createSamFileWriter(final SAMFileHeader header) {
        out = new SAMFileWriterFactory().makeWriter(header,
                header.getSortOrder() == SortOrder.queryname, OUTPUT, REFERENCE_SEQUENCE);

    }

    protected void writeAlignment(final SAMRecord sam) {
        out.addAlignment(sam);
    }

    protected void closeWriter() {
        out.close();
    }

}
