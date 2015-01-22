/* LanguageTool, a natural language style checker 
 * Copyright (C) 2005 Daniel Naber (http://www.danielnaber.de)
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package org.languagetool;

import org.apache.commons.lang.StringUtils;
import org.languagetool.databroker.DefaultResourceDataBroker;
import org.languagetool.databroker.ResourceDataBroker;
import org.languagetool.languagemodel.LanguageModel;
import org.languagetool.markup.AnnotatedText;
import org.languagetool.markup.AnnotatedTextBuilder;
import org.languagetool.rules.*;
import org.languagetool.rules.patterns.FalseFriendRuleLoader;
import org.languagetool.rules.patterns.PatternRule;
import org.languagetool.rules.patterns.PatternRuleLoader;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.jar.Manifest;
import java.util.regex.Pattern;

/**
 * The main class used for checking text against different rules:
 * <ul>
 * <li>the built-in Java rules (for English: <i>a</i> vs. <i>an</i>, whitespace after commas, ...)
 * <li>pattern rules loaded from external XML files with
 * {@link #loadPatternRules(String)}
 * <li>your own implementation of the abstract {@link Rule} classes added with
 * {@link #addRule(Rule)}
 * </ul>
 * 
 * <p>Note that the constructors create a language checker that uses the built-in
 * Java rules only. Other rules (e.g. from XML) need to be added explicitly or
 * activated using {@link #activateDefaultPatternRules()}.
 * 
 * <p>You will probably want to use the sub class {@link MultiThreadedJLanguageTool} for best performance.
 * 
 * <p><b>Thread-safety:</b> this class is not thread safe. Create one instance per thread,
 * but create the language only once (e.g. {@code new English()}) and use it for all
 * instances of JLanguageTool.</p>
 * 
 * @see MultiThreadedJLanguageTool
 */
public class JLanguageTool {

  /** LanguageTool version as a string like {@code 2.3} or {@code 2.4-SNAPSHOT}. */
  public static final String VERSION = "2.9-SNAPSHOT";
  /** LanguageTool build date and time like {@code 2013-10-17 16:10} or {@code null} if not run from JAR. */
  public static final String BUILD_DATE = getBuildDate();

  /** The name of the file with error patterns. */
  public static final String PATTERN_FILE = "grammar.xml";
  /** The name of the file with false friend information. */
  public static final String FALSE_FRIEND_FILE = "false-friends.xml";
  /** The internal tag used to mark the beginning of a sentence. */
  public static final String SENTENCE_START_TAGNAME = "SENT_START";
  /** The internal tag used to mark the end of a sentence. */
  public static final String SENTENCE_END_TAGNAME = "SENT_END";
  /** The internal tag used to mark the end of a paragraph. */
  public static final String PARAGRAPH_END_TAGNAME = "PARA_END";
  /** Name of the message bundle for translations. */
  public static final String MESSAGE_BUNDLE = "org.languagetool.MessagesBundle";

  /**
   * Returns the build date or {@code null} if not run from JAR.
   */
  private static String getBuildDate() {
    try {
      final URL res = JLanguageTool.class.getResource(JLanguageTool.class.getSimpleName() + ".class");
      if (res == null) {
        // this will happen on Android, see http://stackoverflow.com/questions/15371274/
        return null;
      }
      final Object connObj = res.openConnection();
      if (connObj instanceof JarURLConnection) {
        final JarURLConnection conn = (JarURLConnection) connObj;
        final Manifest manifest = conn.getManifest();
        return manifest.getMainAttributes().getValue("Implementation-Date");
      } else {
        return null;
      }
    } catch (IOException e) {
      throw new RuntimeException("Could not get build date from JAR", e);
    }
  }
  
  private static ResourceDataBroker dataBroker = new DefaultResourceDataBroker();

  private final List<Rule> builtinRules;
  private final List<Rule> userRules = new ArrayList<>(); // rules added via addRule() method
  private final Set<String> disabledRules = new HashSet<>();
  private final Set<String> enabledRules = new HashSet<>();
  private final Set<String> disabledCategories = new HashSet<>();

  private final Language language;
  private final Language motherTongue;

  private PrintStream printStream;

  private int sentenceCount;

  private boolean listUnknownWords;
  private Set<String> unknownWords;  

  /**
   * Constants for correct paragraph-rule handling:
   * <ul>
   * <li>NORMAL -  all kinds of rules run</li>
   * <li>ONLYPARA - only paragraph-level rules</li>
   * <li>ONLYNONPARA - only sentence-level rules</li></ul>
   */
  public static enum ParagraphHandling {
    /**
     * Handle normally - all kinds of rules run.
     */
    NORMAL,
    /**
     * Run only paragraph-level rules.
     */
    ONLYPARA,
    /**
     * Run only sentence-level rules.
     */
    ONLYNONPARA
  }
  
  private static final List<File> temporaryFiles = new ArrayList<>();
  
  /**
   * Create a JLanguageTool and setup the built-in Java rules for the
   * given language, ignoring XML-based rules and false friend rules.
   *
   * @param language the language of the text to be checked
   */
  public JLanguageTool(final Language language) {
    this(language, null);
  }

  /**
   * Create a JLanguageTool and setup the built-in Java rules for the
   * given language, ignoring XML-based rules except false friend rules.
   * 
   * @param language the language of the text to be checked
   * @param motherTongue the user's mother tongue, used for false friend rules, or <code>null</code>.
   *          The mother tongue may also be used as a source language for checking bilingual texts.
   */
  public JLanguageTool(final Language language, final Language motherTongue) {
    this.language = Objects.requireNonNull(language, "language cannot be null");
    this.motherTongue = motherTongue;
    final ResourceBundle messages = ResourceBundleTools.getMessageBundle(language);
    builtinRules = getAllBuiltinRules(language, messages);
  }
  
  /**
   * The grammar checker needs resources from following
   * directories:
   * <ul>
   * <li>{@code /resource}</li>
   * <li>{@code /rules}</li>
   * </ul>
   * This method is thread-safe.
   * 
   * @return The currently set data broker which allows to obtain
   * resources from the mentioned directories above. If no
   * data broker was set, a new {@link DefaultResourceDataBroker} will
   * be instantiated and returned.
   * @since 1.0.1
   */
  public static synchronized ResourceDataBroker getDataBroker() {
    if (JLanguageTool.dataBroker == null) {
      JLanguageTool.dataBroker = new DefaultResourceDataBroker();
    }
    return JLanguageTool.dataBroker;
  }
  
  /**
   * The grammar checker needs resources from following
   * directories:
   * <ul>
   * <li>{@code /resource}</li>
   * <li>{@code /rules}</li>
   * </ul>
   * This method is thread-safe.
   * 
   * @param broker The new resource broker to be used.
   * @since 1.0.1
   */
  public static synchronized void setDataBroker(ResourceDataBroker broker) {
    JLanguageTool.dataBroker = broker;
  }

  /**
   * Whether the {@link #check(String)} methods store unknown words. If set to
   * <code>true</code> (default: false), you can get the list of unknown words
   * using {@link #getUnknownWords()}.
   */
  public void setListUnknownWords(final boolean listUnknownWords) {
    this.listUnknownWords = listUnknownWords;
  }

  /**
   * Gets the ResourceBundle (i18n strings) for the default language of the user's system.
   */
  public static ResourceBundle getMessageBundle() {
    return ResourceBundleTools.getMessageBundle();
  }

  /**
   * Gets the ResourceBundle (i18n strings) for the given user interface language.
   * @since 2.4 (public since 2.4)
   */
  public static ResourceBundle getMessageBundle(final Language lang) {
    return ResourceBundleTools.getMessageBundle(lang);
  }
  
  private List<Rule> getAllBuiltinRules(final Language language, ResourceBundle messages) {
    try {
      return language.getRelevantRules(messages);
    } catch (IOException e) {
      throw new RuntimeException("Could not get rules of language " + language, e);
    }
  }

  /**
   * Set a PrintStream that will receive verbose output. Set to
   * {@code null} (which is the default) to disable verbose output.
   */
  public void setOutput(final PrintStream printStream) {
    this.printStream = printStream;
  }

  /**
   * Load pattern rules from an XML file. Use {@link #addRule(Rule)} to add these
   * rules to the checking process.
   *
   * @param filename path to an XML file in the classpath or in the filesystem - the classpath is checked first
   * @return a List of {@link PatternRule} objects
   */
  public List<PatternRule> loadPatternRules(final String filename) throws IOException {
    final PatternRuleLoader ruleLoader = new PatternRuleLoader();
    final InputStream is = this.getClass().getResourceAsStream(filename);
    if (is == null) {
      // happens for external rules plugged in as an XML file:
      return ruleLoader.getRules(new File(filename));
    } else {
      return ruleLoader.getRules(is, filename);
    }
  }

  /**
   * Load false friend rules from an XML file. Only those pairs will be loaded
   * that match the current text language and the mother tongue specified in the
   * JLanguageTool constructor. Use {@link #addRule(Rule)} to add these rules to the
   * checking process.
   *
   * @param filename path to an XML file in the classpath or in the filesystem - the classpath is checked first
   * @return a List of {@link PatternRule} objects, or an empty list if mother tongue is not set
   */
  public List<PatternRule> loadFalseFriendRules(final String filename)
      throws ParserConfigurationException, SAXException, IOException {
    if (motherTongue == null) {
      return new ArrayList<>();
    }
    final FalseFriendRuleLoader ruleLoader = new FalseFriendRuleLoader();
    final InputStream is = this.getClass().getResourceAsStream(filename);
    if (is == null) {
      return ruleLoader.getRules(new File(filename), language, motherTongue);
    } else {
      return ruleLoader.getRules(is, language, motherTongue);
    }
  }

  /**
   * Activate rules that depend on a language model. The language model currently
   * consists of Lucene indexes with ngram occurrence counts.
   * @param indexDir directory with a '3grams' sub directory which contains a Lucene index with 3gram occurrence counts
   * @since 2.7
   */
  public void activateLanguageModelRules(File indexDir) throws IOException {
    LanguageModel languageModel = language.getLanguageModel(indexDir);
    if (languageModel != null) {
      ResourceBundle messages = getMessageBundle(language);
      List<Rule> rules = language.getRelevantLanguageModelRules(messages, languageModel);
      userRules.addAll(rules);
    }
  }

  /**
   * Loads and activates the pattern rules from
   * {@code org/languagetool/rules/<languageCode>/grammar.xml}.
   */
  public void activateDefaultPatternRules() throws IOException {
    final List<PatternRule> patternRules = language.getPatternRules();
    final List<String> enabledRules = language.getDefaultEnabledRulesForVariant();
    final List<String> disabledRules = language.getDefaultDisabledRulesForVariant();
    if (!enabledRules.isEmpty() || !disabledRules.isEmpty()) {
      for (PatternRule patternRule : patternRules) {
        if (enabledRules.contains(patternRule.getId())) {
          patternRule.setDefaultOn();
        }
        if (disabledRules.contains(patternRule.getId())) {
          patternRule.setDefaultOff();
        }
      }
    }
    userRules.addAll(patternRules);
  }

  /**
   * Loads and activates the false friend rules from
   * <code>rules/false-friends.xml</code>.
   */
  public void activateDefaultFalseFriendRules()
      throws ParserConfigurationException, SAXException, IOException {
    final String falseFriendRulesFilename = JLanguageTool.getDataBroker().getRulesDir() + "/" + FALSE_FRIEND_FILE;
    final List<PatternRule> patternRules = loadFalseFriendRules(falseFriendRulesFilename);
    userRules.addAll(patternRules);
  }

  /**
   * Add a rule to be used by the next call to the check methods like {@link #check(String)}.
   */
  public void addRule(final Rule rule) {
    userRules.add(rule);
  }

  /**
   * Disable a given rule so the check methods like {@link #check(String)} won't use it.
   * @param ruleId the id of the rule to disable - no error will be thrown if the id does not exist
   */
  public void disableRule(final String ruleId) {
    disabledRules.add(ruleId);
  }

  /**
   * Disable the given rules so the check methods like {@link #check(String)} won't use them.
   * @param ruleIds the ids of the rules to disable - no error will be thrown if the id does not exist
   * @since 2.4
   */
  public void disableRules(final List<String> ruleIds) {
    disabledRules.addAll(ruleIds);
  }

  /**
   * Disable the given rule category so the check methods like {@link #check(String)} won't use it.
   * 
   * @param categoryName the id of the category to disable - no error will be thrown if the id does not exist
   */
  public void disableCategory(final String categoryName) {
    disabledCategories.add(categoryName);
  }

  /**
   * Get the language that was used to configure this instance.
   */
  public Language getLanguage() {
    return language;
  }

  /**
   * Get rule ids of the rules that have been explicitly disabled.
   */
  public Set<String> getDisabledRules() {
    return disabledRules;
  }

  /**
   * Enable a rule that is switched off by default ({@code default="off"} in the XML).
   * 
   * @param ruleId the id of the turned off rule to enable.
   */
  public void enableDefaultOffRule(final String ruleId) {
    enabledRules.add(ruleId);
  }

  /**
   * Get category ids of the rule categories that have been explicitly disabled.
   */
  public Set<String> getDisabledCategories() {
    return disabledCategories;
  }

  /**
   * Re-enable a given rule so the check methods like {@link #check(String)} will use it.
   * Note that you need to use {@link #enableDefaultOffRule(String)} for rules that
   * are off by default. This will <em>not</em> throw an exception if the given rule id 
   * doesn't exist.
   * 
   * @param ruleId the id of the rule to enable
   */
  public void enableRule(final String ruleId) {
    if (disabledRules.contains(ruleId)) {
      disabledRules.remove(ruleId);
    }
  }

  /**
   * Tokenizes the given text into sentences.
   */
  public List<String> sentenceTokenize(final String text) {
    return language.getSentenceTokenizer().tokenize(text);
  }

  /**
   * The main check method. Tokenizes the text into sentences and matches these
   * sentences against all currently active rules.
   * 
   * @param text the text to be checked
   * @return a List of {@link RuleMatch} objects
   */
  public List<RuleMatch> check(final String text) throws IOException {
    return check(text, true, ParagraphHandling.NORMAL);
  }

  public List<RuleMatch> check(final String text, boolean tokenizeText, final ParagraphHandling paraMode) throws IOException {
    return check(new AnnotatedTextBuilder().addText(text).build(), tokenizeText, paraMode);
  }

  /**
   * The main check method. Tokenizes the text into sentences and matches these
   * sentences against all currently active rules, adjusting error positions so they refer 
   * to the original text <em>including</em> markup.
   * @since 2.3
   */
  public List<RuleMatch> check(final AnnotatedText text) throws IOException {
    return check(text, true, ParagraphHandling.NORMAL);
  }
  
  /**
   * The main check method. Tokenizes the text into sentences and matches these
   * sentences against all currently active rules.
   * 
   * @param annotatedText The text to be checked, created with {@link AnnotatedTextBuilder}. 
   *          Call this method with the complete text to be checked. If you call it
   *          repeatedly with smaller chunks like paragraphs or sentence, those rules that work across
   *          paragraphs/sentences won't work (their status gets reset whenever this method is called).
   * @param tokenizeText If true, then the text is tokenized into sentences.
   *          Otherwise, it is assumed it's already tokenized, i.e. it is only one sentence
   * @param paraMode Uses paragraph-level rules only if true.
   * @return a List of {@link RuleMatch} objects, describing potential errors in the text
   * @since 2.3
   */
  public List<RuleMatch> check(final AnnotatedText annotatedText, boolean tokenizeText, final ParagraphHandling paraMode) throws IOException {
    final List<String> sentences;
    if (tokenizeText) { 
      sentences = sentenceTokenize(annotatedText.getPlainText());
    } else {
      sentences = new ArrayList<>();
      sentences.add(annotatedText.getPlainText());
    }
    final List<Rule> allRules = getAllRules();
    printIfVerbose(allRules.size() + " rules activated for language " + language);

    sentenceCount = sentences.size();
    unknownWords = new HashSet<>();
    final List<AnalyzedSentence> analyzedSentences = analyzeSentences(sentences);
    
    final List<RuleMatch> ruleMatches = performCheck(analyzedSentences, sentences, allRules, paraMode, annotatedText);
    
    Collections.sort(ruleMatches);
    return ruleMatches;
  }
  
  /**
   * Use this method if you want to access LanguageTool's otherwise
   * internal analysis of the text. For actual text checking, use the {@code check...} methods instead.
   * @param text The text to be analyzed 
   * @since 2.5
   */
  public List<AnalyzedSentence> analyzeText(String text) throws IOException {
    final List<String> sentences = sentenceTokenize(text);
    return analyzeSentences(sentences);
  }
  
  private List<AnalyzedSentence> analyzeSentences(final List<String> sentences) throws IOException {
    final List<AnalyzedSentence> analyzedSentences = new ArrayList<>();
    
    int j = 0;
    for (final String sentence : sentences) {
      AnalyzedSentence analyzedSentence = getAnalyzedSentence(sentence);
      rememberUnknownWords(analyzedSentence);
      if (++j == sentences.size()) {
        final AnalyzedTokenReadings[] anTokens = analyzedSentence.getTokens();
        anTokens[anTokens.length - 1].setParagraphEnd();
        analyzedSentence = new AnalyzedSentence(anTokens);
      }
      analyzedSentences.add(analyzedSentence);
      printIfVerbose(analyzedSentence.toString());
      printIfVerbose(analyzedSentence.getAnnotations());
    }
    
    return analyzedSentences;
  }
  
  protected List<RuleMatch> performCheck(final List<AnalyzedSentence> analyzedSentences, final List<String> sentences,
                                         final List<Rule> allRules, ParagraphHandling paraMode, final AnnotatedText annotatedText) throws IOException {
    final Callable<List<RuleMatch>> matcher = new TextCheckCallable(allRules, sentences, analyzedSentences, paraMode, annotatedText, 0, 0, 1);
    try {
      return matcher.call();
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public List<RuleMatch> checkAnalyzedSentence(final ParagraphHandling paraMode,
      final List<Rule> allRules, int charCount, int lineCount, int columnCount,
      final String sentence, final AnalyzedSentence analyzedSentence) throws IOException {
    return checkAnalyzedSentence(paraMode, allRules, charCount, lineCount, columnCount, sentence, analyzedSentence, null);
  }
  
  /**
   * @since 2.3
   */
  public List<RuleMatch> checkAnalyzedSentence(final ParagraphHandling paraMode,
      final List<Rule> rules, int charCount, int lineCount,
      int columnCount, final String sentence, final AnalyzedSentence analyzedSentence, final AnnotatedText annotatedText)
        throws IOException {
    final List<RuleMatch> sentenceMatches = new ArrayList<>();
    for (final Rule rule : rules) {
      if (rule instanceof TextLevelRule) {
        continue;
      }
      if (ignoreRule(rule)) {
        continue;
      }
      if (rule instanceof PatternRule && ((PatternRule)rule).canBeIgnoredFor(analyzedSentence)) {
        // this is a performance optimization, it should have no effect on matching logic
        continue;
      }
      if (paraMode == ParagraphHandling.ONLYPARA) {
        continue;
      }
      final RuleMatch[] thisMatches = rule.match(analyzedSentence);
      for (final RuleMatch element1 : thisMatches) {
        final RuleMatch thisMatch = adjustRuleMatchPos(element1,
            charCount, columnCount, lineCount, sentence, annotatedText);
        sentenceMatches.add(thisMatch);
      }
    }
    final RuleMatchFilter filter = new SameRuleGroupFilter();
    return filter.filter(sentenceMatches);
  }

  private boolean ignoreRule(Rule rule) {
    if (disabledRules.contains(rule.getId())) {
      return true;
    }
    if (rule.isDefaultOff() && !enabledRules.contains(rule.getId())) {
      return true;
    }
    Category category = rule.getCategory();
    if (category != null && disabledCategories.contains(category.getName())) {
      return true;
    }
    return false;
  }

  /**
   * Change RuleMatch positions so they are relative to the complete text,
   * not just to the sentence. 
   * @param charCount Count of characters in the sentences before
   * @param columnCount Current column number
   * @param lineCount Current line number
   * @param sentence The text being checked
   * @return The RuleMatch object with adjustments.
   */
  public RuleMatch adjustRuleMatchPos(final RuleMatch match, int charCount,
      int columnCount, int lineCount, final String sentence, final AnnotatedText annotatedText) {
    int fromPos = match.getFromPos() + charCount;
    int toPos = match.getToPos() + charCount;
    if (annotatedText != null) {
      fromPos = annotatedText.getOriginalTextPositionFor(fromPos);
      toPos = annotatedText.getOriginalTextPositionFor(toPos - 1) + 1;
    }
    final RuleMatch thisMatch = new RuleMatch(match.getRule(),
        fromPos, toPos, match.getMessage(), match.getShortMessage());
    thisMatch.setSuggestedReplacements(match.getSuggestedReplacements());
    final String sentencePartToError = sentence.substring(0, match.getFromPos());
    final String sentencePartToEndOfError = sentence.substring(0, match.getToPos());
    final int lastLineBreakPos = sentencePartToError.lastIndexOf('\n');
    final int column;
    final int endColumn;
    if (lastLineBreakPos == -1) {
      column = sentencePartToError.length() + columnCount;
    } else {
      column = sentencePartToError.length() - lastLineBreakPos;
    }
    final int lastLineBreakPosInError = sentencePartToEndOfError.lastIndexOf('\n');
    if (lastLineBreakPosInError == -1) {
      endColumn = sentencePartToEndOfError.length() + columnCount;
    } else {
      endColumn = sentencePartToEndOfError.length() - lastLineBreakPosInError;
    }
    final int lineBreaksToError = countLineBreaks(sentencePartToError);
    final int lineBreaksToEndOfError = countLineBreaks(sentencePartToEndOfError);
    thisMatch.setLine(lineCount + lineBreaksToError);
    thisMatch.setEndLine(lineCount + lineBreaksToEndOfError);
    thisMatch.setColumn(column);
    thisMatch.setEndColumn(endColumn);
    thisMatch.setOffset(match.getFromPos() + charCount);
    return thisMatch;
  }

  private void rememberUnknownWords(final AnalyzedSentence analyzedText) {
    if (listUnknownWords) {
      final AnalyzedTokenReadings[] atr = analyzedText
          .getTokensWithoutWhitespace();
      for (final AnalyzedTokenReadings reading : atr) {
        if (!reading.isTagged()) {
          unknownWords.add(reading.getToken());
        }
      }
    }
  }

  /**
   * Get the alphabetically sorted list of unknown words in the latest run of one of the {@link #check(String)} methods.
   * 
   * @throws IllegalStateException if {@link #setListUnknownWords(boolean)} has been set to {@code false}
   */
  public List<String> getUnknownWords() {
    if (!listUnknownWords) {
      throw new IllegalStateException("listUnknownWords is set to false, unknown words not stored");
    }
    final List<String> words = new ArrayList<>(unknownWords);
    Collections.sort(words);
    return words;
  }

  // non-private only for test case
  static int countLineBreaks(final String s) {
    int pos = -1;
    int count = 0;
    while (true) {
      final int nextPos = s.indexOf('\n', pos + 1);
      if (nextPos == -1) {
        break;
      }
      pos = nextPos;
      count++;
    }
    return count;
  }

  /**
   * Tokenizes the given {@code sentence} into words and analyzes it,
   * and then disambiguates POS tags.
   *
   * @param sentence sentence to be analyzed
   */
  public AnalyzedSentence getAnalyzedSentence(final String sentence) throws IOException {
    return language.getDisambiguator().disambiguate(getRawAnalyzedSentence(sentence));
  }

  /**
   * Tokenizes the given {@code sentence} into words and analyzes it.
   * This is the same as {@link #getAnalyzedSentence(String)} but it does not run
   * the disambiguator.
   * 
   * @param sentence sentence to be analyzed
   * @since 0.9.8
   */
  public AnalyzedSentence getRawAnalyzedSentence(final String sentence) throws IOException {
    final List<String> tokens = language.getWordTokenizer().tokenize(sentence);
    final Map<Integer, String> softHyphenTokens = replaceSoftHyphens(tokens);

    final List<AnalyzedTokenReadings> aTokens = language.getTagger().tag(tokens);
    if (language.getChunker() != null) {
      language.getChunker().addChunkTags(aTokens);
    }
    final int numTokens = aTokens.size();
    int posFix = 0; 
    for (int i = 1; i < numTokens; i++) {
      aTokens.get(i).setWhitespaceBefore(aTokens.get(i - 1).isWhitespace());
      aTokens.get(i).setStartPos(aTokens.get(i).getStartPos() + posFix);
      if (!softHyphenTokens.isEmpty()) {
        if (softHyphenTokens.get(i) != null) {
          aTokens.get(i).addReading(language.getTagger().createToken(softHyphenTokens.get(i), null));
          posFix += softHyphenTokens.get(i).length() - aTokens.get(i).getToken().length();
        }
      }
    }
        
    final AnalyzedTokenReadings[] tokenArray = new AnalyzedTokenReadings[tokens.size() + 1];
    final AnalyzedToken[] startTokenArray = new AnalyzedToken[1];
    int toArrayCount = 0;
    final AnalyzedToken sentenceStartToken = new AnalyzedToken("", SENTENCE_START_TAGNAME, null);
    startTokenArray[0] = sentenceStartToken;
    tokenArray[toArrayCount++] = new AnalyzedTokenReadings(startTokenArray, 0);
    int startPos = 0;
    for (final AnalyzedTokenReadings posTag : aTokens) {
      posTag.setStartPos(startPos);
      tokenArray[toArrayCount++] = posTag;
      startPos += posTag.getToken().length();
    }

    // add additional tags
    int lastToken = toArrayCount - 1;
    // make SENT_END appear at last not whitespace token
    for (int i = 0; i < toArrayCount - 1; i++) {
      if (!tokenArray[lastToken - i].isWhitespace()) {
        lastToken -= i;
        break;
      }
    }

    tokenArray[lastToken].setSentEnd();

    if (tokenArray.length == lastToken + 1 && tokenArray[lastToken].isLinebreak()) {
      tokenArray[lastToken].setParagraphEnd();
    }
    return new AnalyzedSentence(tokenArray);
  }

  private Map<Integer, String> replaceSoftHyphens(List<String> tokens) {
    Pattern ignoredCharacterRegex = language.getIgnoredCharactersRegex();
    
    final Map<Integer, String> ignoredCharsTokens = new HashMap<>();
    if( ignoredCharacterRegex == null )
      return ignoredCharsTokens;
    
    for (int i = 0; i < tokens.size(); i++) {
      if ( ignoredCharacterRegex.matcher(tokens.get(i)).find() ) {
        ignoredCharsTokens.put(i, tokens.get(i));
        tokens.set(i, ignoredCharacterRegex.matcher(tokens.get(i)).replaceAll(""));
      }
    }
    return ignoredCharsTokens;
  }

  /**
   * Get all rules for the current language that are built-in or that have been
   * added using {@link #addRule(Rule)}.
   * @return a List of {@link Rule} objects
   */
  public List<Rule> getAllRules() {
    final List<Rule> rules = new ArrayList<>();
    rules.addAll(builtinRules);
    rules.addAll(userRules);
    // Some rules have an internal state so they can do checks over sentence
    // boundaries. These need to be reset so the checks don't suddenly
    // work on different texts with the same data. However, it could be useful
    // to keep the state information if we're checking a continuous text.    
    for (final Rule rule : rules) {
      rule.reset();
    }
    return rules;
  }
  
  /**
   * Get all active (not disabled) rules for the current language that are built-in or that 
   * have been added using e.g. {@link #addRule(Rule)}.
   * @return a List of {@link Rule} objects
   */
  public List<Rule> getAllActiveRules() {
    final List<Rule> rules = new ArrayList<>();
    final List<Rule> rulesActive = new ArrayList<>();
    rules.addAll(builtinRules);
    rules.addAll(userRules);
    // Some rules have an internal state so they can do checks over sentence
    // boundaries. These need to be reset so the checks don't suddenly
    // work on different texts with the same data. However, it could be useful
    // to keep the state information if we're checking a continuous text.    
    for (final Rule rule : rules) {
      rule.reset();
      boolean isDisabled = disabledRules.contains(rule.getId()) || (rule.isDefaultOff() && !enabledRules.contains(rule.getId()));
      if (!isDisabled) {
        rulesActive.add(rule);
      }
    }    
    return rulesActive;
  }
  
  /**
   * Get pattern rules by Id and SubId. This returns a list because rules that use {@code <or>...</or>}
   * are internally expanded into several rules.
   * 
   * @return a List of {@link Rule} objects
   * @since 2.3
   */
  public List<PatternRule> getPatternRulesByIdAndSubId(String Id, String subId) {
    final List<Rule> rules = getAllRules();
    final List<PatternRule> rulesById = new ArrayList<>();   
    for (final Rule rule : rules) {
      rule.reset();
      if (rule instanceof PatternRule) {
        if (rule.getId().equals(Id) && ((PatternRule)rule).getSubId().equals(subId)) {
          rulesById.add((PatternRule) rule);
        }
      }
    }    
    return rulesById;
  }
  
  /**
   * Number of sentences the latest call to a check method like {@link #check(String)} has checked.
   * @deprecated use {@link #analyzeText(String)} instead (deprecated since 2.7)
   */
  public int getSentenceCount() {
    return sentenceCount;
  }

  private void printIfVerbose(final String s) {
    if (printStream != null) {
      printStream.println(s);
    }
  }
  
  /**
   * Adds a temporary file to the internal list
   * (internal method, you should never need to call this as a user of LanguageTool)
   * @param file the file to be added.
   */
  public static void addTemporaryFile(final File file) {
    temporaryFiles.add(file);
  }
  
  /**
   * Clean up all temporary files, if there are any.
   */
  public static void removeTemporaryFiles() {
    for (File file : temporaryFiles) {
      file.delete();
    }
  }

  class TextCheckCallable implements Callable<List<RuleMatch>> {

    private final List<Rule> rules;
    private final ParagraphHandling paraMode;
    private final AnnotatedText annotatedText;
    private final List<String> sentences;
    private final List<AnalyzedSentence> analyzedSentences;
    
    private int charCount;
    private int lineCount;
    private int columnCount;

    TextCheckCallable(List<Rule> rules, List<String> sentences, List<AnalyzedSentence> analyzedSentences,
                      ParagraphHandling paraMode, AnnotatedText annotatedText, int charCount, int lineCount, int columnCount) {
      this.rules = rules;
      if (sentences.size() != analyzedSentences.size()) {
        throw new IllegalArgumentException("sentences and analyzedSentences do not have the same length : " + sentences.size() + " != " + analyzedSentences.size());
      }
      this.sentences = sentences;
      this.analyzedSentences = analyzedSentences;
      this.paraMode = paraMode;
      this.annotatedText = annotatedText;
      this.charCount = charCount;
      this.lineCount = lineCount;
      this.columnCount = columnCount;
    }

    @Override
    public List<RuleMatch> call() throws Exception {
      final List<RuleMatch> ruleMatches = new ArrayList<>();
      int i = 0;
      for (Rule rule : rules) {
        if (rule instanceof TextLevelRule && !ignoreRule(rule) && paraMode != ParagraphHandling.ONLYNONPARA) {
          RuleMatch[] matches = ((TextLevelRule) rule).match(analyzedSentences);
          for (RuleMatch match : matches) {
            LineColumnRange range = getLineColumnRange(match);
            match.setColumn(range.from.column);
            match.setEndColumn(range.to.column);
            match.setLine(range.from.line);
            match.setEndLine(range.to.line);
          }
          ruleMatches.addAll(Arrays.asList(matches));
        }
      }
      for (final AnalyzedSentence analyzedSentence : analyzedSentences) {
        final String sentence = sentences.get(i++);
        try {
          final List<RuleMatch> sentenceMatches =
                  checkAnalyzedSentence(paraMode, rules, charCount, lineCount,
                          columnCount, sentence, analyzedSentence, annotatedText);

          ruleMatches.addAll(sentenceMatches);
          charCount += sentence.length();
          lineCount += countLineBreaks(sentence);

          // calculate matching column:
          final int lineBreakPos = sentence.lastIndexOf('\n');
          if (lineBreakPos == -1) {
            columnCount += sentence.length();
          } else {
            if (lineBreakPos == 0) {
              columnCount = sentence.length();
              if (!language.getSentenceTokenizer().singleLineBreaksMarksPara()) {
                columnCount--;
              }
            } else {
              columnCount = sentence.length() - lineBreakPos;
            }
          }
        } catch (Exception e) {
          throw new RuntimeException("Could not check sentence: '"
                  + StringUtils.abbreviate(analyzedSentence.toTextString(), 200) + "'", e);
        }
      }
      return ruleMatches;
    }

    private LineColumnRange getLineColumnRange(RuleMatch match) {
      LineColumnPosition fromPos = new LineColumnPosition(-1, -1);
      LineColumnPosition toPos = new LineColumnPosition(-1, -1);
      LineColumnPosition pos = new LineColumnPosition(0, 0);
      int charCount = 0;
      for (AnalyzedSentence analyzedSentence : analyzedSentences) {
        for (AnalyzedTokenReadings readings : analyzedSentence.getTokens()) {
          String token = readings.getToken();
          if ("\n".equals(token)) {
            pos.line++;
            pos.column = 0;
          }
          pos.column += token.length();
          charCount += token.length();
          if (charCount == match.getFromPos()) {
            fromPos = new LineColumnPosition(pos.line, pos.column);
          } 
          if (charCount == match.getToPos()) {
            toPos = new LineColumnPosition(pos.line, pos.column);
          }
        }
      }
      return new LineColumnRange(fromPos, toPos);
    }
    
    private class LineColumnPosition {
      int line;
      int column;
      private LineColumnPosition(int line, int column) {
        this.line = line;
        this.column = column;
      }
    }
  
    private class LineColumnRange {
      LineColumnPosition from;
      LineColumnPosition to;
      private LineColumnRange(LineColumnPosition from, LineColumnPosition to) {
        this.from = from;
        this.to = to;
      }
    }
  
  }

}
