package cl.camodev.utiles;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import cl.camodev.wosbot.ot.DTOImageSearchResult;
import cl.camodev.wosbot.ot.DTOPoint;
import cl.camodev.wosbot.console.enumerable.EnumTemplates;
import org.slf4j.*;

public class ImageSearchUtil {
	private static final Logger logger = LoggerFactory.getLogger(ImageSearchUtil.class);

	// Cache thread-safe para templates precargados
	private static final ConcurrentHashMap<String, Mat> templateCache = new ConcurrentHashMap<>();
	
	// Cache for grayscale templates
	private static final ConcurrentHashMap<String, Mat> grayscaleTemplateCache = new ConcurrentHashMap<>();

	// Custom thread pool for OpenCV operations
	private static final ForkJoinPool openCVThreadPool = new ForkJoinPool(
		Math.min(Runtime.getRuntime().availableProcessors(), 4)
	);

	// Cache for template byte arrays
	private static final ConcurrentHashMap<String, byte[]> templateBytesCache = new ConcurrentHashMap<>();

	// Cache initialization status
	private static volatile boolean cacheInitialized = false;

	// Thread-local storage for profile name context
	private static final ThreadLocal<String> currentProfileName = new ThreadLocal<>();

	/**
	 * Set the current profile name for logging context.
	 * This is used to prefix log messages with the profile name.
	 *
	 * @param profileName The profile name to use
	 */
	public static void setProfileName(String profileName) {
		currentProfileName.set(profileName);
	}

	/**
	 * Clear the current profile name
	 */
	public static void clearProfileName() {
		currentProfileName.remove();
	}

	/**
	 * Get formatted log message with profile name prefix if available
	 */
	private static String formatLogMessage(String message) {
		String profileName = currentProfileName.get();
		if (profileName != null && !profileName.isEmpty()) {
			return profileName + " - " + message;
		}
		return message;
	}

	static {
		// Automatic cache initialization in the background
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			openCVThreadPool.shutdown();
			// Clean cache and release OpenCV memory
			templateCache.values().forEach(Mat::release);
			templateCache.clear();
			grayscaleTemplateCache.values().forEach(Mat::release);
			grayscaleTemplateCache.clear();
			templateBytesCache.clear();
		}));

		// Preload all templates from the enum in the background
		initializeTemplateCache();
	}

	/**
	 * Initializes the template cache by loading all templates from the EnumTemplates enum.
	 */
	private static void initializeTemplateCache() {
		if (cacheInitialized) return;

		openCVThreadPool.submit(() -> {
			try {
				logger.info("Caching templates...");

				// Preload all templates from the enum (both color and grayscale)
				for (EnumTemplates enumTemplate : EnumTemplates.values()) {
					String templatePath = enumTemplate.getTemplate();
					try {
						// Load color template
						loadTemplateOptimized(templatePath);
						logger.debug(formatLogMessage("Template " + templatePath + " cached successfully"));
						
						// Also load grayscale version
						loadTemplateGrayscale(templatePath);
						logger.debug(formatLogMessage("Grayscale template " + templatePath + " cached successfully"));
					} catch (Exception e) {
						logger.warn(formatLogMessage("Error preloading template " + templatePath + ": " + e.getMessage()));
					}
				}

				cacheInitialized = true;
				logger.info(formatLogMessage("Template cache initialized with " + templateCache.size() + " color templates and " + 
				                             grayscaleTemplateCache.size() + " grayscale templates"));

			} catch (Exception e) {
				logger.error(formatLogMessage("Error initializing template cache: " + e.getMessage()));
			}
		});
	}

	/**
	 * Performs the search for a template within a main image.
	 */
	public static DTOImageSearchResult searchTemplate(byte[] image, String templateResourcePath, DTOPoint topLeftCorner, DTOPoint bottomRightCorner, double thresholdPercentage) {
		// Delegate to the optimized method while maintaining the same signature
		return searchTemplateOptimized(image, templateResourcePath, topLeftCorner, bottomRightCorner, thresholdPercentage);
	}

	/**
	 * Performs the search for multiple matches of a template within a main image.
	 */
	public static List<DTOImageSearchResult> searchTemplateMultiple(byte[] image, String templateResourcePath, DTOPoint topLeftCorner, DTOPoint bottomRightCorner, double thresholdPercentage, int maxResults) {
		// Delegate to the optimized method while maintaining the same signature
		return searchTemplateMultipleOptimized(image, templateResourcePath, topLeftCorner, bottomRightCorner, thresholdPercentage, maxResults);
	}
	
	/**
	 * Performs a grayscale search for a template within a main image.
	 * Both the template and the image are converted to grayscale before matching.
	 */
	public static DTOImageSearchResult searchTemplateGrayscale(byte[] image, String templateResourcePath, DTOPoint topLeftCorner, DTOPoint bottomRightCorner, double thresholdPercentage) {
		return searchTemplateGrayscaleOptimized(image, templateResourcePath, topLeftCorner, bottomRightCorner, thresholdPercentage);
	}
	
	/**
	 * Performs a grayscale search for multiple matches of a template within a main image.
	 * Both the template and the image are converted to grayscale before matching.
	 */
	public static List<DTOImageSearchResult> searchTemplateGrayscaleMultiple(byte[] image, String templateResourcePath, DTOPoint topLeftCorner, DTOPoint bottomRightCorner, double thresholdPercentage, int maxResults) {
		return searchTemplateGrayscaleMultipleOptimized(image, templateResourcePath, topLeftCorner, bottomRightCorner, thresholdPercentage, maxResults);
	}

	/**
	 * Optimized method for loading and caching templates.
	 */
	private static Mat loadTemplateOptimized(String templateResourcePath) {
		// Try to get from cache first
		Mat cachedTemplate = templateCache.get(templateResourcePath);
		if (cachedTemplate != null && !cachedTemplate.empty()) {
			return cachedTemplate.clone(); // Return a copy for thread safety
		}

		try {
			// Load bytes from cache or resource
			byte[] templateBytes = templateBytesCache.computeIfAbsent(templateResourcePath, path -> {
				try (InputStream is = ImageSearchUtil.class.getResourceAsStream(path)) {
					if (is == null) {
						logger.error(formatLogMessage("Template resource not found: " + path));
						return null;
					}
					return is.readAllBytes();
				} catch (IOException e) {
					logger.error(formatLogMessage("Error loading template bytes for: " + path), e);
					return null;
				}
			});

			if (templateBytes == null) {
				return new Mat(); // Empty Mat
			}

			// Decode template
			MatOfByte templateMatOfByte = new MatOfByte(templateBytes);
			Mat template = Imgcodecs.imdecode(templateMatOfByte, Imgcodecs.IMREAD_COLOR);

			if (!template.empty()) {
				// Save to cache (clone to avoid modifications)
				templateCache.put(templateResourcePath, template.clone());
			}

			return template;

		} catch (Exception e) {
			logger.error(formatLogMessage("Exception loading template: " + templateResourcePath), e);
			return new Mat();
		}
	}
	
	/**
	 * Optimized method for loading and caching grayscale templates.
	 */
	private static Mat loadTemplateGrayscale(String templateResourcePath) {
		// Try to get from grayscale cache first
		Mat cachedTemplate = grayscaleTemplateCache.get(templateResourcePath);
		if (cachedTemplate != null && !cachedTemplate.empty()) {
			return cachedTemplate.clone(); // Return a copy for thread safety
		}

		try {
			// Load the color template first
			Mat colorTemplate = loadTemplateOptimized(templateResourcePath);
			if (colorTemplate.empty()) {
				return new Mat();
			}
			
			// Convert to grayscale
			Mat grayTemplate = new Mat();
			Imgproc.cvtColor(colorTemplate, grayTemplate, Imgproc.COLOR_BGR2GRAY);
			
			// Save to grayscale cache
			if (!grayTemplate.empty()) {
				grayscaleTemplateCache.put(templateResourcePath, grayTemplate.clone());
			}
			
			// Release color template as we don't need it anymore
			colorTemplate.release();
			
			return grayTemplate;
		} catch (Exception e) {
			logger.error(formatLogMessage("Exception loading grayscale template: " + templateResourcePath), e);
			return new Mat();
		}
	}

	/**
	 * Optimized version of the searchTemplate method with cache and better memory management.
	 */
	public static DTOImageSearchResult searchTemplateOptimized(byte[] image, String templateResourcePath,
			DTOPoint topLeftCorner, DTOPoint bottomRightCorner, double thresholdPercentage) {

		Mat imagenPrincipal = null;
		Mat template = null;
		Mat imagenROI = null;
		Mat resultado = null;

		try {
			// Quick ROI validation
			int roiX = topLeftCorner.getX();
			int roiY = topLeftCorner.getY();
			int roiWidth = bottomRightCorner.getX() - topLeftCorner.getX();
			int roiHeight = bottomRightCorner.getY() - topLeftCorner.getY();

			if (roiWidth <= 0 || roiHeight <= 0) {
				logger.error(formatLogMessage("Invalid ROI dimensions"));
				return new DTOImageSearchResult(false, null, 0.0);
			}

			// Decoding of main image (reusable)
			MatOfByte matOfByte = new MatOfByte(image);
			imagenPrincipal = Imgcodecs.imdecode(matOfByte, Imgcodecs.IMREAD_COLOR);

			if (imagenPrincipal.empty()) {
				return new DTOImageSearchResult(false, null, 0.0);
			}

			// Load optimized template with cache
			template = loadTemplateOptimized(templateResourcePath);
			if (template.empty()) {
				return new DTOImageSearchResult(false, null, 0.0);
			}

			// ROI vs image validation
			if (roiX + roiWidth > imagenPrincipal.cols() || roiY + roiHeight > imagenPrincipal.rows()) {
				logger.error(formatLogMessage("ROI exceeds image dimensions"));
				return new DTOImageSearchResult(false, null, 0.0);
			}

			// Create ROI
			Rect roi = new Rect(roiX, roiY, roiWidth, roiHeight);
			imagenROI = new Mat(imagenPrincipal, roi);

			// Optimized size check
			int resultCols = imagenROI.cols() - template.cols() + 1;
			int resultRows = imagenROI.rows() - template.rows() + 1;
			if (resultCols <= 0 || resultRows <= 0) {
				return new DTOImageSearchResult(false, null, 0.0);
			}

			// Template matching
			resultado = new Mat(resultRows, resultCols, CvType.CV_32FC1);
			Imgproc.matchTemplate(imagenROI, template, resultado, Imgproc.TM_CCOEFF_NORMED);

			// Search for the best match
			Core.MinMaxLocResult mmr = Core.minMaxLoc(resultado);
			double matchPercentage = mmr.maxVal * 100.0;

			if (matchPercentage < thresholdPercentage) {
				logger.warn(formatLogMessage("Template " + templateResourcePath + " match percentage " + matchPercentage + " below threshold " + thresholdPercentage));
				return new DTOImageSearchResult(false, null, matchPercentage);
			}

			logger.info(formatLogMessage("Template " + templateResourcePath + " found with match percentage: " + matchPercentage));

			// Calculate center coordinates
			Point matchLoc = mmr.maxLoc;
			double centerX = matchLoc.x + roi.x + (template.cols() / 2.0);
			double centerY = matchLoc.y + roi.y + (template.rows() / 2.0);

			return new DTOImageSearchResult(true, new DTOPoint((int) centerX, (int) centerY), matchPercentage);

		} catch (Exception e) {
			logger.error(formatLogMessage("Exception during optimized template search"), e);
			return new DTOImageSearchResult(false, null, 0.0);
		} finally {
			// Explicit release of OpenCV memory
			if (imagenPrincipal != null) imagenPrincipal.release();
			if (template != null) template.release();
			if (imagenROI != null) imagenROI.release();
			if (resultado != null) resultado.release();
		}
	}

	/**
	 * Optimized version for multiple search with parallelization.
	 */
	public static CompletableFuture<List<DTOImageSearchResult>> searchTemplateMultipleAsync(
			byte[] image, String templateResourcePath, DTOPoint topLeftCorner,
			DTOPoint bottomRightCorner, double thresholdPercentage, int maxResults) {

		return CompletableFuture.supplyAsync(() -> searchTemplateMultipleOptimized(image, templateResourcePath,
                topLeftCorner, bottomRightCorner, thresholdPercentage, maxResults), openCVThreadPool);
	}

	/**
	 * Optimized version of multiple search with better memory management.
	 */
	public static List<DTOImageSearchResult> searchTemplateMultipleOptimized(byte[] image,
			String templateResourcePath, DTOPoint topLeftCorner, DTOPoint bottomRightCorner,
			double thresholdPercentage, int maxResults) {

		List<DTOImageSearchResult> results = new ArrayList<>();
		Mat mainImage = null;
		Mat template = null;
		Mat imageROI = null;
		Mat matchResult = null;
		Mat resultCopy = null;

		try {
			// Quick ROI validation
			int roiX = topLeftCorner.getX();
			int roiY = topLeftCorner.getY();
			int roiWidth = bottomRightCorner.getX() - topLeftCorner.getX();
			int roiHeight = bottomRightCorner.getY() - topLeftCorner.getY();

			if (roiWidth <= 0 || roiHeight <= 0) {
				return results;
			}

			// Optimized decoding
			MatOfByte matOfByte = new MatOfByte(image);
			mainImage = Imgcodecs.imdecode(matOfByte, Imgcodecs.IMREAD_COLOR);

			if (mainImage.empty()) {
				return results;
			}

			// Load template with cache
			template = loadTemplateOptimized(templateResourcePath);
			if (template.empty()) {
				return results;
			}

			// Validations
			if (roiX + roiWidth > mainImage.cols() || roiY + roiHeight > mainImage.rows()) {
				return results;
			}

			// Create ROI
			Rect roi = new Rect(roiX, roiY, roiWidth, roiHeight);
			imageROI = new Mat(mainImage, roi);

			int resultCols = imageROI.cols() - template.cols() + 1;
			int resultRows = imageROI.rows() - template.rows() + 1;
			if (resultCols <= 0 || resultRows <= 0) {
				return results;
			}

			// Template matching
			matchResult = new Mat(resultRows, resultCols, CvType.CV_32FC1);
			Imgproc.matchTemplate(imageROI, template, matchResult, Imgproc.TM_CCOEFF_NORMED);

			// Optimized search for multiple matches
			double thresholdDecimal = thresholdPercentage / 100.0;
			resultCopy = matchResult.clone();
			int templateWidth = template.cols();
			int templateHeight = template.rows();

			// Pre-calculate for optimization
			int halfTemplateWidth = templateWidth / 2;
			int halfTemplateHeight = templateHeight / 2;

			while (results.size() < maxResults || maxResults <= 0) {
				Core.MinMaxLocResult mmr = Core.minMaxLoc(resultCopy);
				double matchValue = mmr.maxVal;

				if (matchValue < thresholdDecimal) {
					break;
				}

				Point matchLoc = mmr.maxLoc;
				double centerX = matchLoc.x + roi.x + halfTemplateWidth;
				double centerY = matchLoc.y + roi.y + halfTemplateHeight;

				results.add(new DTOImageSearchResult(true,
					new DTOPoint((int) centerX, (int) centerY), matchValue * 100.0));

				// Optimized suppression
				int suppressX = Math.max(0, (int)matchLoc.x - halfTemplateWidth);
				int suppressY = Math.max(0, (int)matchLoc.y - halfTemplateHeight);
				int suppressWidth = Math.min(templateWidth, resultCopy.cols() - suppressX);
				int suppressHeight = Math.min(templateHeight, resultCopy.rows() - suppressY);

				if (suppressWidth > 0 && suppressHeight > 0) {
					Rect suppressRect = new Rect(suppressX, suppressY, suppressWidth, suppressHeight);
					Mat suppressArea = new Mat(resultCopy, suppressRect);
					suppressArea.setTo(new org.opencv.core.Scalar(0));
					suppressArea.release();
				}
			}

		} catch (Exception e) {
			logger.error(formatLogMessage("Exception during optimized multiple template search"), e);
		} finally {
			// Explicit memory release
			if (mainImage != null) mainImage.release();
			if (template != null) template.release();
			if (imageROI != null) imageROI.release();
			if (matchResult != null) matchResult.release();
			if (resultCopy != null) resultCopy.release();
		}

		return results;
	}

	/**
	 * Performs a grayscale search for a template within a main image.
	 * Both the template and the image are converted to grayscale before matching.
	 */
	public static DTOImageSearchResult searchTemplateGrayscaleOptimized(byte[] image, String templateResourcePath,
			DTOPoint topLeftCorner, DTOPoint bottomRightCorner, double thresholdPercentage) {

		Mat imagenPrincipal = null;
		Mat imagenPrincipalGray = null;
		Mat template = null;
		Mat imagenROI = null;
		Mat resultado = null;

		try {
			// Quick ROI validation
			int roiX = topLeftCorner.getX();
			int roiY = topLeftCorner.getY();
			int roiWidth = bottomRightCorner.getX() - topLeftCorner.getX();
			int roiHeight = bottomRightCorner.getY() - topLeftCorner.getY();

			if (roiWidth <= 0 || roiHeight <= 0) {
				logger.error(formatLogMessage("Invalid ROI dimensions"));
				return new DTOImageSearchResult(false, null, 0.0);
			}

			// Decoding of main image (reusable)
			MatOfByte matOfByte = new MatOfByte(image);
			imagenPrincipal = Imgcodecs.imdecode(matOfByte, Imgcodecs.IMREAD_COLOR);

			if (imagenPrincipal.empty()) {
				return new DTOImageSearchResult(false, null, 0.0);
			}
			
			// Convert main image to grayscale
			imagenPrincipalGray = new Mat();
			Imgproc.cvtColor(imagenPrincipal, imagenPrincipalGray, Imgproc.COLOR_BGR2GRAY);
			imagenPrincipal.release();
			imagenPrincipal = null;

			// Load optimized grayscale template with cache
			template = loadTemplateGrayscale(templateResourcePath);
			if (template.empty()) {
				return new DTOImageSearchResult(false, null, 0.0);
			}

			// ROI vs image validation
			if (roiX + roiWidth > imagenPrincipalGray.cols() || roiY + roiHeight > imagenPrincipalGray.rows()) {
				logger.error(formatLogMessage("ROI exceeds image dimensions"));
				return new DTOImageSearchResult(false, null, 0.0);
			}

			// Create ROI
			Rect roi = new Rect(roiX, roiY, roiWidth, roiHeight);
			imagenROI = new Mat(imagenPrincipalGray, roi);

			// Optimized size check
			int resultCols = imagenROI.cols() - template.cols() + 1;
			int resultRows = imagenROI.rows() - template.rows() + 1;
			if (resultCols <= 0 || resultRows <= 0) {
				return new DTOImageSearchResult(false, null, 0.0);
			}

			// Template matching
			resultado = new Mat(resultRows, resultCols, CvType.CV_32FC1);
			Imgproc.matchTemplate(imagenROI, template, resultado, Imgproc.TM_CCOEFF_NORMED);

			// Search for the best match
			Core.MinMaxLocResult mmr = Core.minMaxLoc(resultado);
			double matchPercentage = mmr.maxVal * 100.0;

			if (matchPercentage < thresholdPercentage) {
				logger.warn(formatLogMessage("Grayscale template " + templateResourcePath + " match percentage " + matchPercentage + " below threshold " + thresholdPercentage));
				return new DTOImageSearchResult(false, null, matchPercentage);
			}

			// Calculate center point of the match (taking ROI into account)
			int centerX = (int) (mmr.maxLoc.x + (double) template.cols() / 2 + roiX);
			int centerY = (int) (mmr.maxLoc.y + (double) template.rows() / 2 + roiY);

			return new DTOImageSearchResult(true, new DTOPoint(centerX, centerY), matchPercentage);

		} catch (Exception e) {
			logger.error(formatLogMessage("Exception during grayscale template search"), e);
			return new DTOImageSearchResult(false, null, 0.0);
		} finally {
			// Explicit memory release for all Mat objects
			if (imagenPrincipal != null) imagenPrincipal.release();
			if (imagenPrincipalGray != null) imagenPrincipalGray.release();
			if (template != null) template.release();
			if (imagenROI != null) imagenROI.release();
			if (resultado != null) resultado.release();
		}
	}
	
	/**
	 * Performs a grayscale search for multiple matches of a template within a main image.
	 * Both the template and the image are converted to grayscale before matching.
	 */
	public static List<DTOImageSearchResult> searchTemplateGrayscaleMultipleOptimized(byte[] image,
			String templateResourcePath, DTOPoint topLeftCorner, DTOPoint bottomRightCorner,
			double thresholdPercentage, int maxResults) {

		List<DTOImageSearchResult> results = new ArrayList<>();
		Mat mainImage = null;
		Mat mainImageGray = null;
		Mat template = null;
		Mat imageROI = null;
		Mat matchResult = null;
		Mat resultCopy = null;

		try {
			// Quick ROI validation
			int roiX = topLeftCorner.getX();
			int roiY = topLeftCorner.getY();
			int roiWidth = bottomRightCorner.getX() - topLeftCorner.getX();
			int roiHeight = bottomRightCorner.getY() - topLeftCorner.getY();

			if (roiWidth <= 0 || roiHeight <= 0) {
				return results;
			}

			// Optimized decoding
			MatOfByte matOfByte = new MatOfByte(image);
			mainImage = Imgcodecs.imdecode(matOfByte, Imgcodecs.IMREAD_COLOR);

			if (mainImage.empty()) {
				return results;
			}
			
			// Convert to grayscale
			mainImageGray = new Mat();
			Imgproc.cvtColor(mainImage, mainImageGray, Imgproc.COLOR_BGR2GRAY);
			mainImage.release();
			mainImage = null;

			// Load grayscale template with cache
			template = loadTemplateGrayscale(templateResourcePath);
			if (template.empty()) {
				return results;
			}

			// Validations
			if (roiX + roiWidth > mainImageGray.cols() || roiY + roiHeight > mainImageGray.rows()) {
				return results;
			}

			// Create ROI
			Rect roi = new Rect(roiX, roiY, roiWidth, roiHeight);
			imageROI = new Mat(mainImageGray, roi);

			int resultCols = imageROI.cols() - template.cols() + 1;
			int resultRows = imageROI.rows() - template.rows() + 1;
			if (resultCols <= 0 || resultRows <= 0) {
				return results;
			}

			// Template matching
			matchResult = new Mat(resultRows, resultCols, CvType.CV_32FC1);
			Imgproc.matchTemplate(imageROI, template, matchResult, Imgproc.TM_CCOEFF_NORMED);

			// Optimized search for multiple matches
			double thresholdDecimal = thresholdPercentage / 100.0;
			resultCopy = matchResult.clone();
			int templateWidth = template.cols();
			int templateHeight = template.rows();

			// Pre-calculate for optimization
			int halfTemplateWidth = templateWidth / 2;
			int halfTemplateHeight = templateHeight / 2;

			while (results.size() < maxResults || maxResults <= 0) {
				Core.MinMaxLocResult mmr = Core.minMaxLoc(resultCopy);
				double matchValue = mmr.maxVal;

				if (matchValue < thresholdDecimal) {
					break;
				}

				Point matchLoc = mmr.maxLoc;
				double centerX = matchLoc.x + roi.x + halfTemplateWidth;
				double centerY = matchLoc.y + roi.y + halfTemplateHeight;

				results.add(new DTOImageSearchResult(true,
					new DTOPoint((int) centerX, (int) centerY), matchValue * 100.0));

				// Optimized suppression
				int suppressX = Math.max(0, (int)matchLoc.x - halfTemplateWidth);
				int suppressY = Math.max(0, (int)matchLoc.y - halfTemplateHeight);
				int suppressWidth = Math.min(templateWidth, resultCopy.cols() - suppressX);
				int suppressHeight = Math.min(templateHeight, resultCopy.rows() - suppressY);

				if (suppressWidth > 0 && suppressHeight > 0) {
					Rect suppressRect = new Rect(suppressX, suppressY, suppressWidth, suppressHeight);
					Mat suppressArea = new Mat(resultCopy, suppressRect);
					suppressArea.setTo(new org.opencv.core.Scalar(0));
					suppressArea.release();
				}
			}

		} catch (Exception e) {
			logger.error(formatLogMessage("Exception during optimized multiple grayscale template search"), e);
		} finally {
			// Explicit memory release
			if (mainImage != null) mainImage.release();
			if (mainImageGray != null) mainImageGray.release();
			if (template != null) template.release();
			if (imageROI != null) imageROI.release();
			if (matchResult != null) matchResult.release();
			if (resultCopy != null) resultCopy.release();
		}

		return results;
	}

	/**
	 * Method for preloading common templates.
	 */
	public static void preloadTemplate(String templateResourcePath) {
		openCVThreadPool.submit(() -> loadTemplateOptimized(templateResourcePath));
	}

	/**
	 * Method to clear cache manually.
	 */
	public static void clearCache() {
		templateCache.values().forEach(Mat::release);
		templateCache.clear();
		grayscaleTemplateCache.values().forEach(Mat::release);
		grayscaleTemplateCache.clear();
		templateBytesCache.clear();
		cacheInitialized = false;
	}

	/**
	 * Search for a template using the EnumTemplates enum directly.
	 */
	public static DTOImageSearchResult searchTemplate(byte[] image, EnumTemplates enumTemplate,
			DTOPoint topLeftCorner, DTOPoint bottomRightCorner, double thresholdPercentage) {
		return searchTemplate(image, enumTemplate.getTemplate(), topLeftCorner, bottomRightCorner, thresholdPercentage);
	}

	/**
	 * Search for multiple templates using the EnumTemplates enum directly.
	 */
	public static List<DTOImageSearchResult> searchTemplateMultiple(byte[] image, EnumTemplates enumTemplate,
			DTOPoint topLeftCorner, DTOPoint bottomRightCorner, double thresholdPercentage, int maxResults) {
		return searchTemplateMultiple(image, enumTemplate.getTemplate(), topLeftCorner, bottomRightCorner, thresholdPercentage, maxResults);
	}
	
	/**
	 * Search for a template using grayscale and the EnumTemplates enum directly.
	 */
	public static DTOImageSearchResult searchTemplateGrayscale(byte[] image, EnumTemplates enumTemplate,
			DTOPoint topLeftCorner, DTOPoint bottomRightCorner, double thresholdPercentage) {
		return searchTemplateGrayscale(image, enumTemplate.getTemplate(), topLeftCorner, bottomRightCorner, thresholdPercentage);
	}

	/**
	 * Search for multiple templates using grayscale and the EnumTemplates enum directly.
	 */
	public static List<DTOImageSearchResult> searchTemplateGrayscaleMultiple(byte[] image, EnumTemplates enumTemplate,
			DTOPoint topLeftCorner, DTOPoint bottomRightCorner, double thresholdPercentage, int maxResults) {
		return searchTemplateGrayscaleMultiple(image, enumTemplate.getTemplate(), topLeftCorner, bottomRightCorner, thresholdPercentage, maxResults);
	}

	/**
	 * Asynchronous version using enum.
	 */
	public static CompletableFuture<List<DTOImageSearchResult>> searchTemplateMultipleAsync(
			byte[] image, EnumTemplates enumTemplate, DTOPoint topLeftCorner,
			DTOPoint bottomRightCorner, double thresholdPercentage, int maxResults) {
		return searchTemplateMultipleAsync(image, enumTemplate.getTemplate(), topLeftCorner, bottomRightCorner, thresholdPercentage, maxResults);
	}

	/**
	 * Checks if the cache is fully initialized.
	 */
	public static boolean isCacheInitialized() {
		return cacheInitialized;
	}

	/**
	 * Gets cache statistics.
	 */
	public static String getCacheStats() {
		return String.format("Templates in cache: %d/%d, Bytes cache: %d",
			templateCache.size(), EnumTemplates.values().length, templateBytesCache.size());
	}

	public static void loadNativeLibrary(String resourcePath) throws IOException {
		// Get the file name from the resource path
		String[] parts = resourcePath.split("/");
		String libFileName = parts[parts.length - 1];

		// Create the lib/opencv directory if it doesn't exist
		File libDir = new File("lib/opencv");
		if (!libDir.exists()) {
			libDir.mkdirs();
		}

		// Create the destination file in lib/opencv
		File destLib = new File(libDir, libFileName);

		// Open the resource as a stream
		try (InputStream in = ImageSearchUtil.class.getResourceAsStream(resourcePath); OutputStream out = new FileOutputStream(destLib)) {
			if (in == null) {
				logger.error(formatLogMessage("Resource not found: " + resourcePath));
				throw new IOException("Resource not found: " + resourcePath);
			}
			byte[] buffer = new byte[4096];
			int bytesRead;
			while ((bytesRead = in.read(buffer)) != -1) {
				out.write(buffer, 0, bytesRead);
			}
		} catch (IOException e) {
			logger.error(formatLogMessage("Error extracting native library: " + e.getMessage()));
			throw e;
		}

		// Load the library using the absolute path of the destination file
		System.load(destLib.getAbsolutePath());
		logger.info(formatLogMessage("Native library loaded from: " + destLib.getPath())); // print a relative path for privacy when sharing logs
	}
}
