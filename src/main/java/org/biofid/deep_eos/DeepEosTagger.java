package org.biofid.deep_eos;

import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence;
import jep.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.uima.UimaContext;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.cas.CAS;
import org.apache.uima.cas.CASException;
import org.apache.uima.fit.component.CasAnnotator_ImplBase;
import org.apache.uima.fit.descriptor.ConfigurationParameter;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.ResourceInitializationException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Properties;

/**
 * Created on 09.10.19.
 */
public class DeepEosTagger extends CasAnnotator_ImplBase {
	
	public static final String PARAM_MODEL_NAME = "modelname";
	@ConfigurationParameter(
			name = PARAM_MODEL_NAME,
			defaultValue = "de"
	)
	private String modelname;
	
	public static final String PARAM_VERBOSE = "verbose";
	@ConfigurationParameter(
			name = PARAM_VERBOSE,
			defaultValue = "false"
	)
	private Boolean verbose;
	
	private Interpreter interp;
	
	public static final String PARAM_PYTHON_SRC = "pythonSrcPath";
	@ConfigurationParameter(
			name = PARAM_PYTHON_SRC,
			defaultValue = "src/main/python",
			mandatory = false
	)
	private String pythonSrcPath;
	
	
	@Override
	public void initialize(UimaContext context) throws ResourceInitializationException {
		super.initialize(context);
		try {
			Properties modelProperties = loadModelProperties();
			if (!modelProperties.containsKey(modelname + ".model")) {
				throw new Exception("The language '" + modelname + "' is not a valid DeepEOS model language!");
			} else {
				ModelConfig modelConfig = new ModelConfig(modelProperties, modelname);
				PyConfig config = new PyConfig();
				config.setPythonHome(Paths.get(System.getenv("HOME") + "/.conda/envs/keras/").toAbsolutePath().toString());
				MainInterpreter.setInitParams(config);
				
				MainInterpreter.setJepLibraryPath(System.getenv("HOME") + "/.conda/envs/keras/lib/python3.7/site-packages/jep/libjep.so");
				interp = new SharedInterpreter();
				interp.exec("import os");
				interp.exec("import sys");
				interp.exec("sys.path.append('" + pythonSrcPath + "')");
				interp.exec("from model import DeepEosModel");
				System.out.println(modelConfig.modelPath);
				interp.exec(String.format("model = DeepEosModel(model_base_path='%s', window_size=%d)", modelConfig.basePath, modelConfig.windowSize));
			}
		} catch (Exception e) {
			throw new ResourceInitializationException(e);
		}
	}
	
	private Properties loadModelProperties() throws IOException {
		Properties properties = new Properties();
		try (InputStream input = getClass().getClassLoader().getResourceAsStream("models.properties")) {
			properties.load(input);
		}
		return properties;
	}
	
	@Override
	public void process(CAS cas) throws AnalysisEngineProcessException {
		String documentText = cas.getDocumentText();
		try {
			JCas jCas = cas.getJCas();
			ArrayList<Long> result = (ArrayList<Long>) interp.invoke("model.tag", documentText);
			int begin = 0;
			for (int i = 0; i < result.size(); i++) {
				Long end = result.get(i);
				Sentence sentence = new Sentence(jCas, begin, Math.toIntExact(end) + 1);
				sentence.setId(String.valueOf(i));
				jCas.addFsToIndexes(sentence);
				begin = Math.toIntExact(end) + 1;
			}
			Sentence sentence = new Sentence(jCas, begin, jCas.getDocumentText().length());
			sentence.setId(String.valueOf(result.size()));
			jCas.addFsToIndexes(sentence);
			
			if (verbose) {
				System.out.println();
				for (Sentence sent : JCasUtil.select(jCas, Sentence.class)) {
					System.out.print(sent);
					System.out.println("   text:" + sent.getCoveredText());
				}
			}
		} catch (JepException | ClassCastException e) {
			throw new AnalysisEngineProcessException(e);
		} catch (CASException e) {
			e.printStackTrace();
		}
	}
	
	@Override
	public void destroy() {
		try {
			this.interp.close();
		} catch (JepException e) {
			e.printStackTrace();
		}
		super.destroy();
	}
	
	private static class ModelConfig {
		final String basePath;
		final String modelPath;
		final String vocabPath;
		final int windowSize;
		
		
		ModelConfig(Properties properties, String modelName) {
			modelPath = properties.getProperty(modelName + ".model");
			basePath = StringUtils.substringBefore(StringUtils.substringBefore(modelPath, ".model"), ".hdf5");
			if (properties.containsKey(modelName + ".vocab")) {
				vocabPath = properties.getProperty(modelName + ".vocab");
			} else {
				vocabPath = StringUtils.substringAfterLast(modelPath, ".") + ".vocab";
			}
			windowSize = Integer.parseInt(properties.getProperty(modelName + ".window_size", "5"));
		}
	}
}
