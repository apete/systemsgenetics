package betaqtl;

import betaqtl.data.Dataset;
import betaqtl.stat.BetaDistributionMLE;
import betaqtl.stat.PVal;
import betaqtl.stat.RankArray;
import betaqtl.vcf.VCFTabix;
import betaqtl.vcf.VCFVariant;
import umcg.genetica.console.ProgressBar;
import umcg.genetica.containers.Triple;
import umcg.genetica.enums.Chromosome;
import umcg.genetica.enums.Strand;
import umcg.genetica.features.Feature;
import umcg.genetica.io.Gpio;
import umcg.genetica.io.text.TextFile;
import umcg.genetica.math.stats.Correlation;
import umcg.genetica.math.stats.ZScores;
import umcg.genetica.text.Strings;
import umontreal.iro.lecuyer.probdist.BetaDist;

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

public class BetaQTL2ParallelCis extends QTLAnalysis {


	public BetaQTL2ParallelCis(String vcfFile, int chromosome, String linkfile, String snpLimitFile, String geneLimitFile, String snpGeneLimitFile, String geneExpressionDataFile, String geneAnnotationFile, String outfile) throws IOException {
		super(vcfFile, chromosome, linkfile, snpLimitFile, geneLimitFile, snpGeneLimitFile, geneExpressionDataFile, geneAnnotationFile, outfile);
		if (datasets.length < minNumberOfDatasets) {
			System.out.println(minNumberOfDatasets + " datasets required, but only " + datasets.length + " datasets defined. Changing setting to: " + datasets.length);
			minNumberOfDatasets = datasets.length;
		}
	}

	DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
	private DecimalFormat dfDefault = new DecimalFormat("#.######", symbols);
	private DecimalFormat dfPval = new DecimalFormat("#.####E0", symbols);


	private int nrPermutations = 1000;
	private int cisWindow = 1000000;
	private long randomSeed = 123456789;
	private boolean rankData = true;
	private boolean outputAll = false;
	private boolean outputSNPLog = false;
	private boolean replaceMissingGenotypes = false;
	private boolean dumpPermutationPvalues = false;

	public void setNrPermutations(int nrPermutations) {
		this.nrPermutations = nrPermutations;
	}

	public void setCisWindow(int cisWindow) {
		this.cisWindow = cisWindow;
	}

	public void setRandomSeed(long randomSeed) {
		this.randomSeed = randomSeed;
	}

	public void setRankData(boolean rankData) {
		this.rankData = rankData;
	}

	public void setOutputAll(boolean outputAll) {
		this.outputAll = outputAll;
	}

	public void setOutputAllPermutations(boolean dumpPermutationPvalues) {
		this.dumpPermutationPvalues = dumpPermutationPvalues;
	}

	public void setOutputSNPLog(boolean outputSNPLog) {
		this.outputSNPLog = outputSNPLog;
	}

	public void setReplaceMissingGenotypes(boolean replaceMissingGenotypes) {
		this.replaceMissingGenotypes = replaceMissingGenotypes;
	}

	enum ANALYSISTYPE {
		CIS,
		TRANS,
		CISTRANS
	}

	private ANALYSISTYPE analysisType = ANALYSISTYPE.CIS;

	public void run() throws IOException {
        /*
        TODO:
        - function to test specific SNPs
        - output dataset Z-scores and sample sizes
        - plotting?
        - check whether code runs with no permutations
        - a way to run on a VCF containing all chromosomes
        - check proper way to shuffle when there is missing data in the genotype as well as the phenotype data
        */

		System.out.println();
		System.out.println("-----------------------------------------------------");
		System.out.println("QTL analysis with Beta distribution approximated null");
		System.out.println("-----------------------------------------------------");
		System.out.println("MAF:\t" + mafthreshold);
		System.out.println("Call-rate:\t" + callratethreshold);
		System.out.println("HWE-P:\t" + hwepthreshold);
		System.out.println("Min nr datasets:\t" + minNumberOfDatasets);
		System.out.println("Nr Permutations:\t" + nrPermutations);
		System.out.println("Cis window:\t" + cisWindow);
		System.out.println("Random seed:\t" + randomSeed);
		System.out.println("Ranking data:\t" + rankData);
//        System.out.println("Re-ranking data:\t");
		System.out.println("Replacing missing genotypes: " + replaceMissingGenotypes);
		System.out.println("Min observations: " + minObservations);
		System.out.println("Writing all snp/feature pairs: " + outputAll);
		System.out.println("Writing SNP log: " + outputSNPLog);
		System.out.println("Writing all permutations: " + dumpPermutationPvalues);
		System.out.println();

		Chromosome chromosomeObj = Chromosome.parseChr("" + chromosome);
		System.out.println("Processing: " + vcfFile);
		if (!Gpio.exists(vcfFile)) {

		}

		// initialize permutation seeds
		long[] seed = new long[nrPermutations];
		Random rand = new Random(randomSeed);
		for (int i = 0; i < seed.length; i++) {
			seed[i] = rand.nextLong();
		}

		String datasetstr = "";
		for (int d = 0; d < datasets.length; d++) {
			if (d == 0) {
				datasetstr = datasets[d].name;
			} else {
				datasetstr += ";" + datasets[d].name;
			}
		}

		// initialize output
		ProgressBar pb = new ProgressBar(expressionData.genes.length, "Processing " + expressionData.genes.length + " genes...");
		TextFile outTopFx = new TextFile(outputPrefix + "-TopEffects.txt", TextFile.W);
		String headerTopFx = "Gene\t" +
				"GeneChr\t" +
				"GenePos\t" +
				"GeneStrand\t" +
				"GeneSymbol\t" +
				"SNP\t" +
				"SNPChr\t" +
				"SNPPos\t" +
				"SNPAlleles\t" +
				"SNPEffectAllele\t" +
				"SNPEffectAlleleFreq\t" +
				"MetaP\t" +
				"MetaPN\t" +
				"MetaPZ\t" +
				"MetaBeta\t" +
				"MetaSE\t" +
				"NrDatasets\t" +
				"DatasetCorrelationCoefficients(" + datasetstr + ")\t" +
				"DatasetZScores(" + datasetstr + ")\t" +
				"DatasetSampleSizes(" + datasetstr + ")\t" +
				"NrTestedSNPs\t" +
				"ProportionBetterPermPvals\t" +
				"BetaDistAlpha\t" +
				"BetaDistBeta\t" +
				"BetaAdjustedMetaP";
		outTopFx.writeln(headerTopFx);
		TextFile outAll = null;
		if (outputAll) {
			outAll = new TextFile(outputPrefix + "-AllEffects.txt.gz", TextFile.W);
//            String headerAll = "Gene\tGeneSymbol\tSNP\tSNPAlleles\tSNPEffectAllele\tMetaP\tMetaPN\tMetaPZ\tMetaBeta\tMetaSE\tNrDatasets\tProportionBetterPermPvals\tBetaAdjustedMetaP";
			String headerAll =
					"Gene\t" +
							"GeneChr\t" +
							"GenePos\t" +
							"GeneStrand\t" +
							"GeneSymbol\t" +
							"SNP\t" +
							"SNPChr\t" +
							"SNPPos\t" +
							"SNPAlleles\t" +
							"SNPEffectAllele\t" +
							"SNPEffectAlleleFreq\t" +
							"MetaP\t" +
							"MetaPN\t" +
							"MetaPZ\t" +
							"MetaBeta\t" +
							"MetaSE\t" +
							"NrDatasets\t" +
							"DatasetCorrelationCoefficients(" + datasetstr + ")\t" +
							"DatasetZScores(" + datasetstr + ")\t" +
							"DatasetSampleSizes(" + datasetstr + ")";
			outAll.writeln(headerAll);
		}

		TextFile permutationoutput = null;
		if (dumpPermutationPvalues) {
			permutationoutput = new TextFile(outputPrefix + "-Permutations.txt.gz", TextFile.W);
			String permHeader = "";
			for (int i = 0; i < nrPermutations; i++) {
				if (i == 0) {
					permHeader += "Perm" + i;
				} else {
					permHeader += "\tPerm" + i;
				}
			}
			permutationoutput.writeln("Gene\tSNP\t" + permHeader);
		}

		// iterate genes
		TextFile finalOutAll = outAll;
		AtomicInteger testedgenes = new AtomicInteger();
		TextFile logout = new TextFile(outputPrefix + "-log.txt.gz", TextFile.W);
		TextFile snplogout = null;
		if (outputSNPLog) {
			snplogout = new TextFile(outputPrefix + "-snpqclog.txt.gz", TextFile.W);
			String snplogheader = "Gene\tVariant\tRefAllele/AltAllele\tOVerallAltAlleleFreq\tNrTotalAlleles\tNrDatasetsPassingQC\tPassQC(" + datasetstr + ")\tMAF(" + datasetstr + ")\tCR(" + datasetstr + ")\tHWE-P(" + datasetstr + ")";
			snplogout.writeln(snplogheader);
		}

		TextFile finalSnplogout = snplogout;
		TextFile finalPermutationoutput = permutationoutput;
		IntStream.range(0, expressionData.genes.length).parallel().forEach(g -> {
			try {
				String gene = expressionData.genes[g];
				Integer geneAnnotationId = geneAnnotation.getGeneId(gene);

				if (geneAnnotationId == null) {
					logout.writelnsynced("Skipping " + gene + " since it has no annotation.");
				} else {
					double[] expData = expressionData.data[g];

					// define CIS window
					int pos = geneAnnotation.getPos(geneAnnotationId);
					Strand strand = geneAnnotation.getStrand(geneAnnotationId);
					String geneSymbol = geneAnnotation.getSymbol(geneAnnotationId);
					int start = pos - cisWindow;
					if (start < 0) {
						start = 0;
					}
					int stop = pos + cisWindow;
					Feature cisRegion = new Feature(chromosomeObj, start, stop);

					// iterate SNPs
					double[] permutationPvals = new double[nrPermutations];
					Arrays.fill(permutationPvals, 1);

					int nrTestedSNPs = 0;

					// split expression data per dataset
					double[][] expressionPerDataset = new double[datasets.length][];
					IntStream.range(0, datasets.length).forEach(d -> {
						Dataset thisDataset = datasets[d];
						double[] datasetExp = thisDataset.select(expData, thisDataset.expressionIds);
						double[] datasetExpRanked = datasetExp;

						if (rankData) {
							RankArray ranker = new RankArray();
							datasetExpRanked = ranker.rank(datasetExp, true); // does this work with NaNs? answer: no
						}
//                        for (int v = 0; v < datasetExp.length; v++) {
//                            System.out.println(thisDataset.name + "\t" + datasetExp[v] + "\t" + datasetExpRanked[v]);
//                        }
						expressionPerDataset[d] = datasetExpRanked;
					});
//                    System.exit(0);

					final UnpermutedResult topUnpermutedResult = new UnpermutedResult(); // this is safe, because values are only changed once per SNP, when permutation == -1

					Set<String> snpLimitSetForGene = snpLimitSet;
					if (snpGeneLimitSet != null) {
						snpLimitSetForGene = snpGeneLimitSet.get(gene);
					}

					Iterator<VCFVariant> snpIterator = null;
					VCFTabix tabix = null;
					if (analysisType == ANALYSISTYPE.CIS) {
						tabix = new VCFTabix(vcfFile);
						snpIterator = tabix.getVariants(cisRegion, genotypeSamplesToInclude, snpLimitSetForGene);
					} else {
						// TODO: NOT IMPLEMENTED YET
					}

					int varctr = 0;
					while (snpIterator.hasNext()) {
						VCFVariant variant = snpIterator.next();

						if (variant != null) {
							varctr++;
							String variantId = variant.getId();
							if ((snpLimitSet == null || snpLimitSet.contains(variantId))
									||
									(snpGeneLimitSet == null || (snpGeneLimitSet.containsKey(gene) && snpGeneLimitSet.get(gene).contains(variantId)))
							) {
								final double[] genotypes = getGenotype(variant.getGenotypesAsByteVector());
								final double[] dosages = getDosage(variant.getDosage());

								// split genotype data per dataset, perform QC
								double[][] genotypesPerDataset = new double[datasets.length][];
								double[][] dosagesPerDataset = new double[datasets.length][];
								VariantQCObj[] qcobjs = new VariantQCObj[datasets.length];
								IntStream.range(0, datasets.length).forEach(d -> {
									Dataset thisDataset = datasets[d];
									dosagesPerDataset[d] = thisDataset.select(dosages, thisDataset.genotypeIds); // select required dosages
									genotypesPerDataset[d] = thisDataset.select(genotypes, thisDataset.genotypeIds); // select required genotype IDs

									VariantQCObj qcobj = checkVariant(genotypesPerDataset[d]);
									if (qcobj.passqc) {
										if (replaceMissingGenotypes) {
											// only replace missing genotypes on variants that pass the qc thresholds
											double meanDosage = Util.meanGenotype(dosagesPerDataset[d]);
											double meanGenotype = Util.meanGenotype(genotypesPerDataset[d]);
											for (int i = 0; i < dosagesPerDataset[d].length; i++) {
												if (genotypesPerDataset[d][i] == -1) {
													genotypesPerDataset[d][i] = meanGenotype;
													dosagesPerDataset[d][i] = meanDosage;
												}
											}
										}


										// prune the data here once, to check if there are enough values to go ahead with this snp/gene combo
										// but only if the variant is passing the QC in the first place for the samples selected in this dataset
										Triple<double[], double[], double[]> prunedDatasetData = pruneMissingValues(genotypesPerDataset[d],
												dosagesPerDataset[d],
												expressionPerDataset[d]);

										// check the variant again, taking into account missingness in the expression data
										qcobj = checkVariant(prunedDatasetData.getLeft());

										// require minimum number of observations, otherwise kick out dataset from analysis
										if (prunedDatasetData.getLeft().length < minObservations) {
											qcobj.passqc = false;
										}
									}
									qcobjs[d] = qcobj;
								});

//                                System.out.println("");
								// run permutations, and non-permuted result (permutation == -1)
								AtomicBoolean tested = new AtomicBoolean(false);
//							TextFile finalOutAll = outAll;
								double[] permutationPvalsForSNP = null;
								if (dumpPermutationPvalues) {
									permutationPvalsForSNP = new double[nrPermutations];
									Arrays.fill(permutationPvalsForSNP, 1);
								}
								double[] finalPermutationPvalsForSNP = permutationPvalsForSNP;
								IntStream.range(-1, nrPermutations).forEach(permutation -> {
									double[] zscores = new double[datasets.length];
									double[] correlations = new double[datasets.length];
									int[] samplesizes = new int[datasets.length];
									Arrays.fill(samplesizes, -1);
									Arrays.fill(zscores, Double.NaN);
									Arrays.fill(correlations, Double.NaN);

									// iterate datasets
									int nrAltAlleles = 0;
									int nrTotalAlleles = 0;
									int dsWithMinObs = 0;
									int nrsnpspassqc = 0;

									for (int d = 0; d < datasets.length; d++) {
										Dataset thisDataset = datasets[d];
										double[] datasetGt = genotypesPerDataset[d]; // thisDataset.select(genotypes, thisDataset.genotypeIds); // select required genotype IDs
										VariantQCObj qcobj = qcobjs[d]; // check maf, hwep, call-rate, number of genotypes per genotype group
										if (qcobj.passqc) {
											nrsnpspassqc++;
											double[] datasetExp = expressionPerDataset[d];
											double[] datasetExpCopy = new double[datasetExp.length];
											System.arraycopy(datasetExp, 0, datasetExpCopy, 0, datasetExpCopy.length);

											double[] datasetDs = dosagesPerDataset[d];

											// if this is a permutation, shuffle the data
											if (permutation != -1) {
												Util.shuffleArray(datasetExpCopy, seed[permutation]);
											}

											// prune the data (remove missing values)
											// can't prune the data earlier (would save a lot of compute time) because shuffling is performed over all available samples for this dataset
											// this is because the order of permuted samples should be equal across all SNPs
											Triple<double[], double[], double[]> prunedDatasetData = pruneMissingValues(datasetGt,
													datasetDs,
													datasetExpCopy);

											// re-rank data here? original EMP does not, but it is the right thing to do...
											double[] datasetExpPruned = prunedDatasetData.getRight();
											if (datasetExpPruned.length >= minObservations) {
												dsWithMinObs++;
//                                    if (rankData) {
//                                        RankArray ranker = new RankArray();
//                                        datasetExpPruned = ranker.rank(datasetExpPruned, true); // does this work with NaNs? answer: no
//                                    }
												datasetExpPruned = Util.centerScale(datasetExpPruned);
												// count the number of alleles, used later to estimate Beta and SE from MetaZ
												double[] datasetDsPruned = prunedDatasetData.getMiddle();
												double[] datasetDsPrunedCopy = prunedDatasetData.getMiddle();
												double[] datasetGtPruned = prunedDatasetData.getLeft();
												if (permutation == -1) {
													for (int i = 0; i < datasetGtPruned.length; i++) {
														if (datasetDsPruned[i] >= 0.5 && datasetDsPruned[i] <= 1.5) {
															nrAltAlleles += 1;
														} else if (datasetDsPruned[i] > 1.5) {
															nrAltAlleles += 2;
														}
													}
													nrTotalAlleles += datasetGtPruned.length * 2;
												}
												datasetDsPruned = Util.centerScale(prunedDatasetData.getMiddle());
												// datasetGtPruned = Util.centerScale(prunedDatasetData.getLeft());

//                                                for (int v = 0; v < datasetDsPruned.length; v++) {
//                                                    System.out.println(thisDataset.name + "\t" + datasetDsPruned[v] + "\t" + datasetExpPruned[v]);
//                                                }

												// perform correlation
												double r = Correlation.correlate(datasetDsPruned, datasetExpPruned);
												double p = PVal.getPvalue(r, datasetExpPruned.length - 2);
												double z = ZScores.pToZTwoTailed(p); // p value is already two-tailed, so need to use this other p-value conversion method... :/; returns negative z-scores by default
												if (r > 0) {
													z *= -1; // flip z-score if correlation is positive because p-value conversion returns only negative z-scores
												}

												if (Double.isNaN(r)) {
													// this happens if there is no variance in the expression or genotype data
													r = 0;
													p = 1;
													z = 0;
												}

												zscores[d] = z;
												correlations[d] = r;
												samplesizes[d] = datasetExpPruned.length;

											} // endif nrobservations >= minobservations
										} // endif qcobj.passqc
									} // ENDfor: test every dataset

									// determine number of datasets with data
									int nDatasets = 0;
									int totalSampleSize = 0;
									for (int d = 0; d < zscores.length; d++) {
										if (!Double.isNaN(zscores[d])) {
											totalSampleSize += samplesizes[d];
											nDatasets++;
										}
									}
									double overallAltAlleleFreq = (double) nrAltAlleles / nrTotalAlleles;
									// write some log stuff
									if (permutation == -1) {
										try {
											if (outputSNPLog) {
												String passStr = "";
												String mafStr = "";
												String crStr = "";
												String hweStr = "";
												for (int d = 0; d < qcobjs.length; d++) {
													if (d == 0) {
														if (qcobjs[d].passqc) {
															passStr = "T";
														} else {
															passStr = "F";
														}
														mafStr = "" + dfDefault.format(qcobjs[d].maf);
														crStr = "" + dfDefault.format(qcobjs[d].cr);
														hweStr = "" + toNeatP(qcobjs[d].hwep);
													} else {
														if (qcobjs[d].passqc) {
															passStr += ";T";
														} else {
															passStr += ";F";
														}
														mafStr += ";" + dfDefault.format(qcobjs[d].maf);
														crStr += ";" + dfDefault.format(qcobjs[d].cr);
														hweStr += ";" + toNeatP(qcobjs[d].hwep);
													}
												}

												String snplogStr = gene + "\t" +
														variantId + "\t" +
														variant.getAlleles()[0] + "/" +
														variant.getAlleles()[1] + "\t" +
														dfDefault.format(overallAltAlleleFreq) + "\t" +
														nrTotalAlleles + "\t" +
														nrsnpspassqc + "\t" +
														passStr + "\t" +
														mafStr + "\t" +
														crStr + "\t" +
														hweStr;
												finalSnplogout.writelnsynced(snplogStr);
											}

											if (snpGeneLimitSet != null && snpGeneLimitSet.get(gene) != null) {
												logout.writelnsynced(gene + "\t" + variantId + " effect is present in  " + nDatasets + " and has " + dsWithMinObs + " with >= " + minObservations + ", " + nrsnpspassqc + " of the dataset SNPs pass QC thresholds");
											}
										} catch (IOException e) {
											e.printStackTrace();
										}
									}


									if (nDatasets >= minNumberOfDatasets) {
										// meta-analyze, weight by sample size
										double metaZ = ZScores.getWeightedZ(zscores, samplesizes);
										double metaP = ZScores.zToP(metaZ);

										if (permutation != -1) { // this is a permuted result
											if (metaP < permutationPvals[permutation]) {
												permutationPvals[permutation] = metaP;
											}
											if (dumpPermutationPvalues) {
												finalPermutationPvalsForSNP[permutation] = metaP;
											}
										} else { // this is a non-permuted result
											tested.getAndSet(true);

											// calculate overall MAF

											double[] betaAndSEEstimate = ZScores.zToBeta(metaZ, overallAltAlleleFreq, totalSampleSize);

											// non-permuted p-value
											if (metaP <= topUnpermutedResult.metaP) {
												boolean replace = true;
												// if the SNP is in perfect LD (has equal pvalue), select the closest one to the gene
												if (metaP == topUnpermutedResult.metaP && topUnpermutedResult.snpID != null) {
													// if the Z-score is sufficiently large (>40) we exceed the range of the normal distribution, returning a p-value of ~2x10-232
													// in that case, compare the absolute Z-scores to determine the top effect for this gene
													if (Math.abs(metaZ) < Math.abs(topUnpermutedResult.metaPZ)) {
														replace = false;
													} else if (Math.abs(metaZ) > Math.abs(topUnpermutedResult.metaPZ)) {
														replace = true;
													} else { // if the Z-scores are also equal (unlikely)
														int genePos = geneAnnotation.getPos(geneAnnotationId);
														int tssDist = Math.abs(genePos - variant.getPos());
														int tssDist2 = Math.abs(genePos - topUnpermutedResult.snpPos);
														if (tssDist > tssDist2) {
															replace = false;
														}
													}
												}
												if (replace) {
													topUnpermutedResult.metaP = metaP;
													topUnpermutedResult.metaPN = totalSampleSize;
													topUnpermutedResult.metaPZ = metaZ;
													topUnpermutedResult.metaPD = nDatasets;
													topUnpermutedResult.zscores = zscores;
													topUnpermutedResult.samplesizes = samplesizes;
													topUnpermutedResult.correlations = correlations;
													topUnpermutedResult.snpEffectAlleleFreq = overallAltAlleleFreq;
													topUnpermutedResult.metaBeta = betaAndSEEstimate[0];
													topUnpermutedResult.metaBetaSE = betaAndSEEstimate[1];
													topUnpermutedResult.snpID = variant.getId();
													topUnpermutedResult.snpPos = variant.getPos();
													topUnpermutedResult.snpAlleles = variant.getAlleles()[0] + "/" + variant.getAlleles()[1];
													topUnpermutedResult.snpEffectAllele = variant.getAlleles()[1];
												}
											}

											if (outputAll) { // this code only runs when in the 'not-permuted' iteration
												String snpAlleles = variant.getAlleles()[0] + "/" + variant.getAlleles()[1];
												String snpEffectAllele = variant.getAlleles()[1];

												String outln = gene
														+ "\t" + chromosome
														+ "\t" + pos
														+ "\t" + strand
														+ "\t" + geneSymbol
														+ "\t" + variant.getId()
														+ "\t" + chromosome
														+ "\t" + variant.getPos()
														+ "\t" + snpAlleles
														+ "\t" + snpEffectAllele
														+ "\t" + dfDefault.format(overallAltAlleleFreq)
														+ "\t" + metaP
														+ "\t" + totalSampleSize
														+ "\t" + dfDefault.format(metaZ)
														+ "\t" + dfDefault.format(betaAndSEEstimate[0])
														+ "\t" + dfDefault.format(betaAndSEEstimate[1])
														+ "\t" + nDatasets
														+ "\t" + toNeatStr(correlations)
														+ "\t" + toNeatStr(zscores)
														+ "\t" + toNeatSampleSizeStr(samplesizes);

												try {
													finalOutAll.writelnsynced(outln);
												} catch (IOException e) {
													e.printStackTrace();
												}

											}
										}
									}
								}); // ENDIF: for each permutation


								// determine if SNP was tested somehow...
								if (tested.get()) {
									if (dumpPermutationPvalues) {
										try {
											finalPermutationoutput.writelnsynced(gene + "\t" + variant.getId() + "\t" + Strings.concat(finalPermutationPvalsForSNP, Strings.tab));
										} catch (IOException e) {
											e.printStackTrace();
										}
									}
									nrTestedSNPs++;
								}


							}  // ENDIF: variant in snpGeneLimitSet || snpLimitSet
						} // ENDIF: if variant != null


					} // ENDIF: while snpiterator has next

					if (nrTestedSNPs == 0) {
						logout.writelnsynced(gene + " has " + varctr + " SNPs in the CIS-window, but none passed QC.");
					} else {
						// determine beta distribution etc
						double propBetterPvals = 0;
						double betaAdjPval = 1;
						double[] shape = new double[]{Double.NaN, Double.NaN};
						BetaDist betaDistribution = null;
						boolean output = true;
						if (nrPermutations > 1) { // permutations are required for the following step
							for (int p = 0; p < permutationPvals.length; p++) {
								if (permutationPvals[p] <= topUnpermutedResult.metaP) {
									propBetterPvals++;
								}
							}
							propBetterPvals /= permutationPvals.length;
							try {
								BetaDistributionMLE mle = new BetaDistributionMLE();
								shape = mle.fit(permutationPvals);
								betaDistribution = new BetaDist(shape[0], shape[1]);
								betaAdjPval = betaDistribution.cdf(topUnpermutedResult.metaP);
								if (betaAdjPval < 2.0E-323D) {
									betaAdjPval = 2.0E-323D;
								}
							} catch (org.apache.commons.math3.exception.TooManyEvaluationsException tmee) {
								logout.writelnsynced(gene + " failed: Beta MLE Model did not converge.");
//                                System.out.println("Pvalue: " + topUnpermutedResult.metaP);
//                                for (int p = 0; p < permutationPvals.length; p++) {
//                                    System.out.println(p + " --> " + permutationPvals[p]);
//                                }
								betaAdjPval = propBetterPvals;
//                                System.exit(0);
								output = false;
							}
						} else {
							propBetterPvals = 1;
						}

						if (output) {
							String outln = gene + "\t" + chromosome + "\t" + pos + "\t" + strand + "\t" + geneSymbol
									+ "\t" + topUnpermutedResult.snpID
									+ "\t" + chromosome
									+ "\t" + topUnpermutedResult.snpPos
									+ "\t" + topUnpermutedResult.snpAlleles
									+ "\t" + topUnpermutedResult.snpEffectAllele
									+ "\t" + dfDefault.format(topUnpermutedResult.snpEffectAlleleFreq)
									+ "\t" + topUnpermutedResult.metaP
									+ "\t" + topUnpermutedResult.metaPN
									+ "\t" + dfDefault.format(topUnpermutedResult.metaPZ)
									+ "\t" + dfDefault.format(topUnpermutedResult.metaBeta)
									+ "\t" + dfDefault.format(topUnpermutedResult.metaBetaSE)
									+ "\t" + topUnpermutedResult.metaPD
									+ "\t" + toNeatStr(topUnpermutedResult.correlations)
									+ "\t" + toNeatStr(topUnpermutedResult.zscores)
									+ "\t" + toNeatSampleSizeStr(topUnpermutedResult.samplesizes)
									+ "\t" + nrTestedSNPs
									+ "\t" + dfDefault.format(propBetterPvals)
									+ "\t" + dfDefault.format(shape[0])
									+ "\t" + dfDefault.format(shape[1])
									+ "\t" + betaAdjPval;
							outTopFx.writelnsynced(outln);
							testedgenes.getAndIncrement();
						}
					}
					tabix.close();
				} // ENDIF: if the gene has an annotation
			} catch (IOException e) {
				e.printStackTrace();
			}
//			pb.set(g + 1);
			pb.iterateSynchedPrint();
		}); // ENDIF: iterate genes

		outTopFx.close();
		if (outputAll) {
			outAll.close();
		}
		if (dumpPermutationPvalues) {
			permutationoutput.close();
		}
		pb.close();
		logout.close();
		if (outputSNPLog) {
			snplogout.close();
		}
		TextFile outFinished = new TextFile(outputPrefix + "-TopEffects.finished", TextFile.W);
		outFinished.writeln("Tested genes:\t" + testedgenes.get());
		outFinished.close();
	}

	private String toNeatP(double pval) {
		if (pval <= 0) {
			return "0";
		} else if (pval == 1) {
			return "1";
		} else if (pval < 0.00001) {
			return "" + dfPval.format(pval);
		} else {
			return "" + dfDefault.format(pval);
		}
	}

	private String toNeatStr(double[] vals) {
		StringBuilder b = new StringBuilder();
		for (int d = 0; d < vals.length; d++) {
			if (d > 0) {
				b.append(";");
			}
			if (Double.isNaN(vals[d])) {
				b.append("-");
			} else {
				b.append("" + dfDefault.format(vals[d]));
			}
		}
		return b.toString();
	}

	private String toNeatStr(int[] vals) {
		StringBuilder b = new StringBuilder();
		for (int d = 0; d < vals.length; d++) {
			if (d > 0) {
				b.append(";");
			}
			b.append("" + vals[d]);
		}
		return b.toString();
	}

	private String toNeatSampleSizeStr(int[] vals) {
		StringBuilder b = new StringBuilder();
		for (int d = 0; d < vals.length; d++) {
			if (d > 0) {
				b.append(";");
			}
			if (vals[d] < 0) {
				b.append("-");
			} else {
				b.append("" + vals[d]);
			}
		}
		return b.toString();
	}

	private class UnpermutedResult {
		public double metaBeta;
		public double metaBetaSE;
		public String snpID;
		public String snpAlleles;
		public String snpEffectAllele;
		public int snpPos;
		public double snpEffectAlleleFreq;
		public double[] zscores;
		public int[] samplesizes;
		public double[] correlations;
		double metaP = 1;
		double metaPN = 0;
		double metaPZ = 0;
		double metaPD = 0;

		@Override
		public String toString() {
			return "UnpermutedResult{" +
					"metaP=" + metaP +
					", metaPN=" + metaPN +
					", metaPZ=" + metaPZ +
					", metaPD=" + metaPD +
					", snpID=" + snpID +
					'}';
		}

	}


}