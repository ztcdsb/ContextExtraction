package at.rovo.textextraction;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.almworks.sqlite4java.SQLiteConnection;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteStatement;
import at.rovo.classifier.naiveBayes.NaiveBayes;
import at.rovo.classifier.naiveBayes.ProbabilityCalculation;
import at.rovo.classifier.naiveBayes.TrainingDataStorageMethod;
import at.rovo.parser.Tag;
import at.rovo.parser.Token;
import at.rovo.parser.Word;
import at.rovo.textextraction.mss.TrainingEntry;
import at.rovo.textextraction.mss.TrainingStrategy;

/**
 * <p>
 * Is the base class for every text extraction algorithm.
 * </p>
 * <p>
 * Text extraction algorithm should have the possibility to predict the text
 * from training examples.
 * </p>
 * <p>
 * Of course there is also the wish to extract the text of an article or web
 * page.
 * </p>
 * 
 * @see MaximumSubsequenceSegmentation
 * @author Roman Vottner
 */
public abstract class TextExtractor 
{
	/** The logger of this class **/
	private static Logger logger = LogManager.getLogger(TextExtractor.class.getName());
	/** Defines the source where to train the classifier from **/
	protected TrainData trainFrom = TrainData.FILE;
	/** The classifier which needs to be trained **/
	protected NaiveBayes<String, String> classifier = null;
	/** The map-structure containing common tags shared by multiple sources **/
	protected Dictionary<String, List<String>> commonTags = new Hashtable<String, List<String>>();
	/** Indicates if the instance is trained or is in need of training **/
	protected boolean isTrained = false;
	/** Specifies how many tokens should be combined or how many features built
	 * from training data examples **/
	protected TrainingStrategy trainingStrategy = TrainingStrategy.TRIPLE_UNIGRAM;
	/** Specifies how many samples per sources should be trained **/
	protected int trainingSampleSize = 0;
	
	/**
	 * <p>Returns the currently set strategy for training new samples.
	 * By default {@link TrainingStrategy#TRIPLE_UNIGRAM} is set</p>
	 * 
	 * @return The currently set strategy for training new samples
	 */
	public TrainingStrategy getTrainingStrategy() { return this.trainingStrategy; }
	
	/**
	 * <p>Sets the new strategy for training new samples to the classifier. 
	 * {@link TrainingStrategy#TRIGRAM} will train f.e. 'token1 token2 token3' 
	 * to the classifier while {@link TrainingStrategy#TRIPLE_UNIGRAM} will
	 * train 'token1', 'token2', 'token3' as either 'in' or 'out' to the
	 * classifier.</p>
	 * 
	 * @param trainingStrategy The training strategy to be used
	 */
	public void setTrainingStrategy(TrainingStrategy trainingStrategy) { this.trainingStrategy = trainingStrategy; }
	
	/**
	 * <p>Tries to predict the text based on either a certain heuristic or
	 * based on some previous training.</p>
	 * @param url The URL of the page whose text should be predicted
	 * @return The predicted article's text
	 * @throws ExtractionException Will be thrown if during the prediction an
	 *                             error occurs
	 */
	public abstract String predictText(String url) throws ExtractionException;
	
	/**
	 * <p>Tries to predict the text for the provided pages based on either a 
	 * certain heuristic or based on some previous training.</p>
	 * 
	 * @param urls The pages whose content should be predicted
	 * @return A list containing all predictions for the provided pages
	 * @throws ExtractionException Will be thrown if during the prediction an
	 *                             error occurs
	 */
	public abstract List<String> predictText(List<String> urls) throws ExtractionException;
	
	/**
	 * <p>Removes unwanted parts of the extracted text</p>
	 * 
	 * @param text Text to remove unwanted parts from
	 * @param renameUnknownTags Specifies if tags which aren't included in
	 *                          the common tags should be renamed to "unknown"
	 * @return The cleaned text
	 */
	public abstract List<Token> cleanText(List<Token> text);
	
	/**
	 * <p>Initializes and starts the training of the classifier</p>
	 */
	public final void initTrainingSamples(int trainingSizePerSource)
	{
		this.trainingSampleSize = trainingSizePerSource;
		
		long startTime = 0;
		logger.info("Start training");
		startTime = System.currentTimeMillis();
		
		if (this.trainFrom.equals(TrainData.FILE))
			this.initTrainingSamplesFromFiles();
		else if (this.trainFrom.equals(TrainData.DB))
			this.initTrainingSamplesFromDB(trainingSizePerSource);
		else
		{
			this.initTrainingSamplesFromDB(trainingSizePerSource);
			this.initTrainingSamplesFromFiles();
		}
		// give children the possibility to extend training
		this.extendTraining();
		
		long neededTime = System.currentTimeMillis()-startTime;
		long min = neededTime/1000/60;
		long lsec = neededTime - min*1000*60;
		long sec = lsec/1000;
		logger.info("Training done. Time needed: {} min {} sec ({} ms)", min, sec, neededTime);
		this.isTrained = true;
	}
	
	/**
	 * <p>This method provides a possibility to train either from additional sources 
	 * and/or to refine training by train new classifiers based on the base classifier</p>
	 */
	protected void extendTraining()
	{
		
	}
	
	/**
	 * <p>Loads the training data from a
	 */
	protected final void initTrainingSamplesFromFiles()
	{
		String userDir = System.getProperty("user.dir");
		File trainingDir = new File(userDir+"/trainingData");

		if (!trainingDir.isDirectory())
		{
			logger.error("Could not find training Directory!");
			return;
		}
		
		// list all files in the trainingData directory
		for (String fileName : trainingDir.list())
		{
			File file = new File(trainingDir.getAbsoluteFile()+"/"+fileName);
			// check if the file is a text file
			if (!file.isDirectory() && file.getName().endsWith(".txt"))
			{
				long startTime = 0L;
				logger.info("Using File {} for training", file.getAbsolutePath());
				startTime = System.currentTimeMillis();
				
				TrainingEntry entry = new TrainingEntry();
				NaiveBayes<String, String> classifier = null;
				
				try
				{
					FileInputStream fis = new FileInputStream(file.getAbsolutePath());
					InputStreamReader in = new InputStreamReader(fis, "UTF-8");
					BufferedReader br = new BufferedReader(in);
					try
					{				
						String line = null;
						StringBuilder text = new StringBuilder();
						int lineNr = 0;
						while ((line = br.readLine()) != null)
						{
							if (lineNr == 0)
							{
								entry.setUrl(line);
								// extract the domain-name to use it as key for a map
								String url = line;
								if (url.startsWith("http://"))
									url = line.substring("http://".length());
								if (url.contains("/"))
									url = url.substring(0, url.indexOf("/"));

								if (this.classifier == null)
									this.classifier = NaiveBayes.create(
											ProbabilityCalculation.EVEN_LIKELIHOOD, 
											TrainingDataStorageMethod.MAP);
								classifier = this.classifier;
								
								entry.setClassifier(classifier);
							}
							else if (lineNr == 1)
								entry.setCategory(line);
							else
								text.append(" "+line);
							
							lineNr++;
						}
						entry.setCommonTags(this.commonTags);
						entry.setText(text.toString());
						
						// start training
						entry.train(false);
					}
					catch (Exception ex)
					{
						logger.catching(ex);
					}
					finally
					{
						br.close();
						in.close();
						fis.close();
						
						br = null;
						in = null;
						fis = null;
					}
				}
				catch (IOException ioEx)
				{
					logger.catching(ioEx);
				}
				
				logger.info("\tTraining took {} ms", (System.currentTimeMillis()-startTime));
			}
		}
	}
	
	/**
	 * <p>Loads the training data from a SQLite database named ate.db which
	 * was used by Jeff Pasternack and Dan Roth to train their algorithm.</p>
	 * <p>The DB-file has to be inside the trainingData sub-directory of the 
	 * project root</p>
	 * <p></p>
	 */
	protected final void initTrainingSamplesFromDB(int trainingSizePerSource)
	{
		// find the directory where the db containing the training-data
		// is located in
		String userDir = System.getProperty("user.dir");
		File trainingDir = new File(userDir+"/trainingData");

		if (!trainingDir.isDirectory())
		{
			logger.error("Could not find training Directory!");
			return;
		}
		
		// well known training sources
		List<String> sources = new ArrayList<String>();
		sources.add("abcnews.go.com");
		sources.add("cnn.com");
		sources.add("foxnews.com");
		sources.add("latimes.com");
		sources.add("news.bbc.co.uk");
		sources.add("news.com.com");
		sources.add("news.yahoo.com");
		sources.add("nytimes.com");
		sources.add("reuters.com");
		sources.add("usatoday.com");
		sources.add("washingtonpost.com");
		sources.add("wired.com");
		
		// directory found
		// first check if there are already serialized objects present
		FilenameFilter filter = new FilenameFilter()
		{
			public boolean accept(File dir, String name)
			{
				if (name.endsWith("_"+trainingSampleSize+"_"+trainingStrategy.name()+".ser"))
					return true;
				return false;
			}
		};
		
		File[] serObjects = trainingDir.listFiles(filter);
		if (serObjects.length > 0)
		{
			logger.info("Trying to reuse previously trained classifier");
			for (File serObject : serObjects)
			{
				if (serObject.getName().equals("commonTags.ser"))
				{
					this.commonTags = loadCommonTags(serObject);
					continue;
				}
				
				NaiveBayes<String, String> cls = NaiveBayes.create(
						ProbabilityCalculation.EVEN_LIKELIHOOD, 
						TrainingDataStorageMethod.MAP);
				if (!cls.loadData(serObject))
					logger.error("Failure loading data file for {}", cls);
				this.classifier = cls;
			}
		}
		else
		{
			// either no or not all serialized objects have been found - train them from the db
			String dbFile = trainingDir.getAbsoluteFile()+"\\ate.db";
			logger.info("Train classifiers from scratch! Using {}, Strategy used: {}", 
					dbFile, this.trainingStrategy.name());
			// create a new db-object
			SQLiteConnection db = new SQLiteConnection(new File(dbFile));
		    try 
		    {
		    	// open a new db connection
				db.open(true);
				for (int i=0; i<sources.size(); i++)
				{
					SQLiteStatement st = db.prepare("SELECT p.Source, p.URL, p.HTML, e.Start, e.Length " +
							"FROM Extractions AS e, Pages AS p " +
							"WHERE e.DocumentID=p.DocumentID AND p.Source='"+sources.get(i)
							+"' Limit "+trainingSizePerSource);
					// this.printTableHeader(st);
		
					NaiveBayes<String, String> classifier = null;
					// run through every found entry and store required data in
					// a TrainingEntry object which will later on used to train
					// the local classifier
					while (st.step())
					{
						TrainingEntry entry = new TrainingEntry();
						// if there is already a classifier for this source, use it
						// else create a new one
						String source = st.columnString(0);
						if (this.classifier == null)
							this.classifier = NaiveBayes.create(
									ProbabilityCalculation.EVEN_LIKELIHOOD, 
									TrainingDataStorageMethod.MAP);
						this.classifier.setName("AllInOne");
						classifier = this.classifier;
						
						entry.setTrainingStrategy(this.trainingStrategy);
						entry.setClassifier(classifier);
						entry.setUrl(st.columnString(1));
						entry.setSourceUrl(source);
						entry.setCommonTags(this.commonTags);
						String html = st.columnString(2);
						html = html.replaceAll("\\s", " ");
						entry.setHTML(html);
						
						// labeled article text extraction
						// as the DB only contains the start position and the length of the 
						// text we have to extract it from the full-HTML code retrieved in the
						// previous step
						logger.debug("start: {}", st.columnInt(3));
						logger.debug("length: {}", st.columnInt(4));
						logger.debug("source: {}", st.columnString(0));
						logger.debug("html: {}", html);
						
						String text = html.substring(st.columnInt(3), st.columnInt(3)+st.columnInt(4));
						entry.setFormatedText(text);
						logger.debug("text: {}", entry.getText());
						entry.train(false);
						// clear the memory obtained by the entry
						entry = null;
					}
					st.dispose();
				}
				// serialize the classifier so we do not have to train it on every new call
				this.classifier.saveData(trainingDir, "mssClassificationData"+"_"+this.trainingSampleSize+"_"+this.trainingStrategy.name()+".ser");
				this.saveCommonTags(this.commonTags, trainingDir, "commonTags");
			} 
		    catch (SQLiteException e) 
			{
		    	logger.catching(e);
			}
		    finally
		    {
		    	db.dispose();
		    }
		}
	}
	
	/**
	 * <p>Loads a list of common tags from a file called 'commonTags.ser' via java
	 * object serialization into memory.</p>
	 * 
	 * @param serializedObject A reference to the {@link File} representing
	 *                         the serialized object
	 * @return The object containing the common tags; null otherwise
	 */
	@SuppressWarnings("unchecked")
	private final Dictionary<String, List<String>> loadCommonTags(File serializedObject)
	{
		Dictionary<String, List<String>> dict = null;
		try
		{
			FileInputStream fis = new FileInputStream(serializedObject);
			BufferedInputStream bis = new BufferedInputStream(fis);
			ObjectInputStream ois = new ObjectInputStream(bis);
			try
			{
				Object obj = ois.readObject();
				if (obj instanceof Hashtable<?, ?>)
				{
					dict = (Hashtable<String, List<String>>)obj;
					logger.info("Found a list of common tags: {} - {}", serializedObject.getName(), dict);
				}
				else if (obj instanceof Dictionary<?, ?>)
				{
					dict = (Dictionary<String, List<String>>)obj;
					logger.info("Found a list of common tags: {} - {}", serializedObject.getName(), dict);
				}
			}
			catch (IOException | ClassNotFoundException e) 
			{
				logger.catching(e);
			}
			finally
			{
				if (ois != null)
					ois.close();
				if (bis != null)
					bis.close();
				if (fis != null)
					fis.close();
			}
		}
		catch (IOException e) 
		{
			logger.catching(e);
		}
		return dict;
	}

	
	/**
	 * <p>Persists the List of common tags via java object serialization to a file in
	 * a defined directory.</p>
	 * 
	 * @param dict The {@link Dictionary} of common tags with their sources they occurred
	 * @param directory The directory the {@link Dictionary} should be saved in
	 * @param name The name of the {@link File} which will hold the bytes of the 
	 *             persisted object
	 */
	private final void saveCommonTags(Dictionary<String, List<String>> dict, File directory, String name)
	{
		try 
		{
			FileOutputStream fos = new FileOutputStream(directory.getAbsoluteFile()+"\\"+name+".ser");
			BufferedOutputStream bos = new BufferedOutputStream(fos);
			ObjectOutput object = null;
			try 
			{
				object = new ObjectOutputStream(bos);
				object.writeObject(dict);
			} 
			catch (IOException e) 
			{
				logger.catching(e);
			}
			finally
			{
				if (object != null)
					object.close();
				if (bos != null)
					bos.close();
				if (fos != null)
					fos.close();
			}
		} 
		catch (IOException e)
		{
			logger.catching(e);
		}
	}

	/**
	 * <p>Formats the text in a more human readable form</p>
	 * 
	 * @param text The {@link List} of {@link Token}s which should be formated
	 * @return The formated text
	 */
	protected String formatText(List<Token> text)
	{
		StringBuilder builder = new StringBuilder();
		builder.append("\n");
		boolean blank = false;
		boolean append = true;
		boolean newLine = true;
		Token lastToken = null;
		for (Token t : text)
		{
			// if the last token was a word and this token is a word add a blank before the new token: 'word1 word2'
			if (blank && append && t instanceof Word && newLine == false)
				builder.append(" ");
			// if the last token was a tag and it was a closing tag and the new token is a word add a blank: '</a> text'
			// only if the new word is neither a . or :
			if ((append && lastToken instanceof Tag && !((Tag)lastToken).isOpeningTag()) && t instanceof Word && 
					!t.getText().equals(":") && !t.getText().equals(".") && newLine == false)
				builder.append(" ");
			// create a blank before a link if the last token was a word: 'word <a href...>'
			if (append && t instanceof Tag && lastToken instanceof Word && ((Tag)t).isOpeningTag() && 
					((Tag)t).getShortTag().equals("a") && newLine == false)
				builder.append(" ");
			
			if (t instanceof Tag)
			{
				blank = false;
				Tag tag = (Tag)t;
				// if the text contains <article>...</article> segments only use the part between those tags as content
				if (tag.getShortTag().equals("article") || tag.getShortTag().equals("more"))
				{ 
					if(tag.isOpeningTag())
						append = true;
					else 
						append = false;
				}
				// don't show special HTML tags
				if (append && !tag.getShortTag().equals("p") && !tag.getShortTag().equals("cite") && !tag.getShortTag().equals("li") && 
						!tag.getShortTag().equals("strong") && !tag.getShortTag().equals("i") && !tag.getShortTag().equals("b") &&
						!tag.getShortTag().equals("ul") && !tag.getShortTag().equals("span") && !tag.getShortTag().matches("h[1-6]") &&
						!tag.getShortTag().equals("article") && !tag.getShortTag().equals("abbr") && !tag.getShortTag().equals("em"))
				{
					builder.append(t.getHTML());
					newLine = false;
				}
				// insert a new line segment for certain HTML tags
				if (!tag.isOpeningTag() && append && (tag.getShortTag().equals("p") ||tag.getShortTag().matches("h1")))
				{
					builder.append("\n\n");
					newLine = true;
				}
				if (!tag.isOpeningTag() && (tag.getShortTag().matches("h[2-6]") || tag.getShortTag().equals("li") || tag.getShortTag().equals("cite")))
				{
					builder.append("\n");
					newLine = true;
				}
				// insert a blank after a span-tag
				if (!tag.isOpeningTag() && append && (tag.getShortTag().equals("span")) && builder.capacity()>0 && newLine==false)
					builder.append(" ");
			}
			else
			{
				if (append)
				{
					if (!t.getText().trim().equals(""))
					{
						builder.append(t.getText());
						newLine = false;
						blank = true;
					}
				}
			}
			lastToken = t;
		}
		
		// TODO: Replace with existing conversion method/library
		// replace special character encodings
		String txt = builder.toString();
		txt = txt.replaceAll("’", "'");
		txt = txt.replaceAll("–", "-");
		txt = txt.replaceAll("—", "-");
		txt = txt.replaceAll("‘", "'");
		txt = txt.replaceAll("’", "'");
		txt = txt.replaceAll(" ", " ");
		txt = txt.replaceAll("“", "\"");
		txt = txt.replaceAll("”", "\"");
		txt = txt.replaceAll("£", "L");
		
		txt = txt.replaceAll("â€“", "-");
		txt = txt.replaceAll("â€œ", "\"");
		txt = txt.replaceAll("â€", "\"");
			
		txt = txt.replaceAll("&quot;", "\"");
		txt = txt.replaceAll("&amp;", "&");
		txt = txt.replaceAll("&nbsp;", " ");
		txt = txt.replaceAll("&rsquo;", "'");
		txt = txt.replaceAll("&mdash;", "-");
		txt = txt.replaceAll("&ldquo;", "\"");
		txt = txt.replaceAll("&rdquo;", "\"");
		
		txt = txt.replaceAll("&#32;", " ");
		txt = txt.replaceAll("&#39;", "'");
		txt = txt.replaceAll("&#160;", " ");
		
		txt = txt.replaceAll("&#039;", "'");	
		txt = txt.replaceAll("&#8217;", "'");
		txt = txt.replaceAll("&#8220;", "\"");
		txt = txt.replaceAll("&#8221;", "\"");
		
		txt = txt.replace("+ -", "+/-");
					
		// remove links and only present text
		txt = txt.replaceAll("<a .*?>(.*?)</a>", "$1");
		txt = txt.replaceAll("</a>", "");
		txt = txt.replaceAll("<a .*?>", "");
		
		txt = txt.replace("<hr>", "");
		
		// sometimes div-tags seem to be cut off inappropriately - delete their
		// garbage
		txt = txt.replaceAll("id=.*?>", "");
		txt = txt.replaceAll("class=.*?>", "");	
		return txt.trim();
	}
}
