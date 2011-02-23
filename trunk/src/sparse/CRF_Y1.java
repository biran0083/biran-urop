package sparse;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import sparse.Instance.Entry;

import edu.stanford.nlp.optimization.CGMinimizer;
import edu.stanford.nlp.optimization.DiffFunction;

//only capture one large feature
public class CRF_Y1 {
	double[] w;

	// seen label set
	// maps label set to corresponding feature weight index in w[]
	Map<Set<Integer>, Integer> S;

	File paramFile = null;
	File outputParamFile = null;
	double lambda = 0.5, bias = 1;

	double F(Set<Integer> y, double[] xw_buf) {
		double res = 0;
		for (Integer i : y)
			res += xw_buf[i];
		return res;
	}

	double G(double[] _w, Set<Integer> y) {
		Integer idx = S.get(y);
		return idx != null ? _w[idx] : 0;
	}

	// x is described by xw_buf
	double Z(double[] _w, double[] xw_buf) {
		double res = 1.0;
		for (int i = 0; i < xw_buf.length; i++)
			res *= 1 + Math.exp(xw_buf[i]);
		for (Map.Entry<Set<Integer>, Integer> entry : S.entrySet()) {
			Set<Integer> y = entry.getKey();
			res -= Math.exp(F(y, xw_buf));
			res += Math.exp(F(y, xw_buf) + _w[entry.getValue()]);
		}
		// System.out.println(res);
		return res;
	}

	double P_Y(double[] _w, Set<Integer> y, double[] xw_buf) {
		return Math.exp(F(y, xw_buf) + G(_w, y)) / Z(_w, xw_buf);
	}

	double[] createXwBuf(double[] _w, Instance ins) {
		double[] xwBuf = new double[ins.L];
		for (int i = 0; i < ins.L; i++)
			xwBuf[i] = ins.dotProdX(_w, i * ins.N);
		return xwBuf;
	}

	double P_Y_l(double[] _w, int l, double[] xw_buf) {
		double down = 1.0, up = 1.0;
		for (int i = 0; i < xw_buf.length; i++)
			down *= 1 + Math.exp(xw_buf[i]);
		up = down * Math.exp(xw_buf[l]) / (1 + Math.exp(xw_buf[l]));
		for (Map.Entry<Set<Integer>, Integer> entry : S.entrySet()) {
			Set<Integer> y = entry.getKey();
			double delta = Math.exp(F(y, xw_buf) + _w[entry.getValue()])
					- Math.exp(F(y, xw_buf));
			if (y.contains(l))
				up += delta;
			down += delta;
		}
		return up / down;
	}

	class LogLikelihood implements DiffFunction {
		Dataset train; // training set

		LogLikelihood(Dataset train) {
			this.train = train;
		}

		@Override
		public double valueAt(double[] _w) {
			double res = 0;
			for (int i = 0; i < train.D; i++) {
				Instance ins = train.data.get(i);
				double[] xwBuf = createXwBuf(_w, ins);

				double delta = 0.0;
				delta = F(ins.y, xwBuf) + G(_w, ins.y) - Math.log(Z(_w, xwBuf));
				res += delta;
				// System.out.println(delta+" "+Math.log(P_Y(_w, ins.y,
				// xwBuf)));
			}
			double panelty = 0;
			for (int i = 0; i < w.length; i++)
				panelty += _w[i] * _w[i];
			panelty *= lambda / 2.0;
			res -= panelty;
			// System.out.println(res);
			return res;
		}

		@Override
		public int domainDimension() {
			return w.length;
		}

		@Override
		public double[] derivativeAt(double[] _w) {
			double[] res = new double[_w.length];

			for (int i = 0; i < train.D; i++) {
				Instance ins = train.data.get(i);
				double[] xwBuf = createXwBuf(_w, ins);
				for (int l = 0; l < ins.L; l++) {
					double temp = (ins.y.contains(l) ? 1.0 : 0.0)
							- P_Y_l(_w, l, xwBuf);
					for (Entry e : ins.x)
						res[l * ins.N + e.idx] += temp * e.val;
				}

				for (Map.Entry<Set<Integer>, Integer> entry : S.entrySet()) {
					Set<Integer> y = entry.getKey();
					int j = entry.getValue();
					res[j] += (ins.y.equals(y) ? 1.0 : 0.0) - P_Y(_w, y, xwBuf);
				}
			}
			for (int i = 0; i < res.length; i++) {
				res[i] -= _w[i] * lambda; // regularization
				/*
				 * double t1=this.valueAt(_w); _w[i]+=0.01; double
				 * t2=this.valueAt(_w); _w[i]-=0.01;
				 * System.out.println(res[i]+" "+(t2-t1)/0.01);
				 */
			}
			return res;
		}

	}

	class NegativeLogLikelihood implements DiffFunction {
		LogLikelihood l;

		NegativeLogLikelihood(LogLikelihood l) {
			this.l = l;
		}

		@Override
		public double valueAt(double[] _w) {
			return -l.valueAt(_w);
		}

		@Override
		public int domainDimension() {
			return l.domainDimension();
		}

		@Override
		public double[] derivativeAt(double[] _w) {
			double[] res = l.derivativeAt(_w);
			for (int i = 0; i < res.length; i++)
				res[i] = -res[i];
			return res;
		}

	};

	void train(Dataset trainSet) throws IOException {
		trainSet.setBias(bias);
		int baseLen = trainSet.L * trainSet.N;
		S = new HashMap<Set<Integer>, Integer>();
		Set<Integer> allOne = new HashSet<Integer>();
		for (int i = 0; i < trainSet.L; i++)
			allOne.add(i);
		S.put(allOne, baseLen);
		if (paramFile != null)
			w = initializeParam(paramFile);
		else
			w = new double[baseLen + S.size()];
		CGMinimizer optimizer = new CGMinimizer(false);
		DiffFunction f = new NegativeLogLikelihood(new LogLikelihood(trainSet));
		w = optimizer.minimize(f, 1e-3, w);

		if (outputParamFile != null)
			this.printParam(outputParamFile);
	}

	// best y given ins.x
	Set<Integer> predict(Instance ins) {
		Set<Integer> res = null;
		double bestP = -1;
		double[] xwBuf = this.createXwBuf(w, ins);
		for (Map.Entry<Set<Integer>, Integer> entry : S.entrySet()) {
			Set<Integer> y = entry.getKey();
			double t = P_Y(w, y, xwBuf);
			if (t > bestP) {
				bestP = t;
				res = y;
			}
		}
		Set<Integer> lrBest = new HashSet<Integer>();
		double lrP = 0;
		for (int i = 0; i < ins.L; i++) {
			if (xwBuf[i] > 0) {
				lrP += xwBuf[i];
				lrBest.add(i);
			}
		}
		lrP = P_Y(w, lrBest, xwBuf);
		// System.out.println(lrP + " " + P_Y(lrBest, xwBuf));
		if (lrP > bestP) {
			bestP = lrP;
			res = lrBest;
		}
		// System.out.println(bestP);
		return res;
	}

	Result test(Dataset testSet) {
		testSet.setBias(bias);
		ArrayList<Set<Integer>> pred = new ArrayList<Set<Integer>>();
		for (int i = 0; i < testSet.D; i++)
			pred.add(predict(testSet.data.get(i)));
		return new Result(testSet, pred);
	}

	void printParam(File f) {
		PrintWriter pr = null;
		try {
			pr = new PrintWriter(new FileWriter(f));
		} catch (IOException e) {
			e.printStackTrace();
			return;
		}
		for (int i = 0; i < w.length; i++)
			pr.printf("%f ", w[i]);
		pr.printf("\n");
		pr.close();
	}

	double[] initializeParam(File f) throws IOException {
		BufferedReader br = new BufferedReader(new FileReader(f));
		String s = br.readLine();
		br.close();
		String[] ss = s.split(" ");
		double[] theta = new double[ss.length];
		for (int i = 0; i < theta.length; i++)
			theta[i] = Double.valueOf(ss[i]);
		return theta;
	}

	void selectBestParameter(Dataset trainSet) throws IOException {
		int trainset_size = trainSet.D * 7 / 10;
		Collections.shuffle(trainSet.data);
		Dataset trainSub = new Dataset(trainSet, 0, trainset_size), validationSet = new Dataset(
				trainSet, trainset_size, trainSet.D);

		double[] lambda_cand = { 1e-4, 1e-3, 0.01, 0.1 };
		double[] bias_cand = { 1e-2, 1e-1, 1, 10 };
		double best_lambda = 0, best_bias = 0;
		double best_val = -1;
		for (int i = 0; i < lambda_cand.length; i++)
			for (int j = 0; j < bias_cand.length; j++) {
				this.lambda = lambda_cand[i];
				this.bias = bias_cand[j];

				this.train(trainSub);

				Result t = this.test(validationSet);
				if (t.macroaverageF > best_val) {
					best_val = t.macroaverageF;
					best_lambda = this.lambda;
					best_bias = this.bias;
				}
				System.out.println("" + "Lambda: " + this.lambda + "\t"
						+ "Bias: " + this.bias + "\t" + "\t"
						+ "macroaverage F: " + t.macroaverageF);
			}

		this.lambda = best_lambda;
		this.bias = best_bias;

		System.out.println("\n\n" + "Best_Lambda: " + this.lambda + "\t"
				+ "Best_Bias: " + this.bias + "\t" + "macrosaverafeF: "
				+ best_val);
	}

	public static void main(String[] args) throws IOException {
		if (args.length < 2) {
			System.err.printf("at least 2 parameters required:\n"
					+ "training data file name\n" + "testing data file name\n");
			return;
		}

		File outputMeasureFile = null;
		boolean selectBestOaram = false;

		CRF_Y1 crf = new CRF_Y1();

		for (int i = 2; i < args.length; i++) {
			if (args[i].equals("-w")) {
				i++;
				crf.paramFile = new File(args[i]);
			} else if (args[i].equals("-s")) {
				selectBestOaram = true;
			} else if (args[i].equals("-ow")) {
				i++;
				crf.outputParamFile = new File(args[i]);
			} else if (args[i].equals("-om")) {
				i++;
				outputMeasureFile = new File(args[i]);
			} else {
				System.err.println("unknown flag: " + args[i]);
				return;
			}
		}
		File train_file = new File(args[0]), test_file = new File(args[1]);

		Dataset trainSet = new Dataset(train_file), testSet = new Dataset(
				test_file);

		if (selectBestOaram)
			crf.selectBestParameter(trainSet);
		crf.train(trainSet);
		Result res = crf.test(testSet);
		if (outputMeasureFile != null)
			res.printMeasures(outputMeasureFile);
		else
			System.out.println(res);

	}

}
