package br.com.careermatcher.similarity;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static br.com.careermatcher.similarity.SimilaritySnapshot.*;

class ForaTest {
    static SimilaritySettings.Parameters parameters() { return new SimilaritySettings().parameters(); }
    static SimilaritySnapshot fixture() {
        var evidence = new ArrayList<Evidence>();
        add(evidence, 1, 1, 5); add(evidence, 1, 2, 3); add(evidence, 1, 3, 2);
        add(evidence, 2, 1, 4); add(evidence, 2, 4, 2); add(evidence, 3, 4, 1);
        add(evidence, 4, 1, 1); add(evidence, 5, 9, 1);
        return new SimilaritySnapshot(List.of(new Profile(1,"A","Dev"), new Profile(2,"B","Dev"),
                new Profile(3,"C","Dev"), new Profile(4,"D","Dev"), new Profile(5,"Disconnected","Dev"),
                new Profile(6,"Isolated","Dev")), evidence);
    }
    static void add(List<Evidence> es, long c, long s, int n) {
        for (int i=0; i<n; i++) es.add(new Evidence(c,s,"Skill"+s,SourceType.EXPERIENCE,i));
    }
    @Test void countsDistinctProvenanceAndSeparatesIdentities() {
        var facts = new ArrayList<Evidence>();
        facts.add(new Evidence(1,1,"Java",SourceType.RESUME_DECLARATION,1));
        for (int i=0;i<2;i++) {
            facts.add(new Evidence(1,1,"Java",SourceType.EXPERIENCE,i));
            facts.add(new Evidence(1,1,"Java",SourceType.PROJECT,i));
        }
        facts.addAll(List.copyOf(facts));
        var g = new SkillProjection(new SimilaritySnapshot(List.of(new Profile(1,"A","Dev")), facts));
        assertThat(g.frequency(1,1)).isEqualTo(5);
        assertThat(g.nodes).hasSize(2);
        assertThat(g.multiplicity).isEqualTo(10);
        assertThat(g.evidence.values().iterator().next()).hasSize(5);
    }
    @Test void sigmaAndTransitionsEqualExpandedMultigraph() {
        var g = new SkillProjection(fixture()); var sigma = g.sigma(1);
        assertThat(Arrays.stream(sigma).sum()).isCloseTo(1, within(1e-15));
        assertThat(sigma[g.index.get(new SkillProjection.Node(SkillProjection.Type.SKILL,1))]).isEqualTo(.5);
        assertThat(sigma[g.index.get(new SkillProjection.Node(SkillProjection.Type.SKILL,2))]).isEqualTo(.3);
        assertThat(sigma[g.index.get(new SkillProjection.Node(SkillProjection.Type.SKILL,3))]).isEqualTo(.2);
        for (int i=0;i<g.nodes.size();i++) {
            if (g.nodes.get(i).type()==SkillProjection.Type.CANDIDATE) assertThat(sigma[i]).isZero();
            var expanded = new ArrayList<Integer>();
            for (var a:g.adjacency.get(i)) for(int j=0;j<a.weight();j++) expanded.add(a.target());
            for (var a:g.adjacency.get(i)) assertThat((double)Collections.frequency(expanded,a.target())/expanded.size())
                    .isEqualTo((double)a.weight()/g.degree[i]);
            assertThat(g.adjacency.get(i).stream().mapToDouble(a->(double)a.weight()/expanded.size()).sum()).isCloseTo(1,within(1e-15));
        }
    }
    @Test void pushInvariantAndMonteCarloAgreeWithIterativeOracleAcrossSeeds() {
        var g = new SkillProjection(fixture()); var sigma = g.sigma(1);
        var p=parameters(); var budget = new Fora.Budget(p,System::nanoTime);
        var push=Fora.push(g,sigma,p.alpha(),.02,budget);
        assertThat(Arrays.stream(push.reserve()).sum()+Arrays.stream(push.residual()).sum()).isCloseTo(1,within(1e-12));
        var exact=oracle(g,sigma,p.alpha());
        var reconstructed=push.reserve().clone();
        for(int v=0;v<sigma.length;v++) {
            double[] unit = new double[sigma.length]; unit[v]=1;
            var pv=oracle(g,unit,p.alpha());
            for(int t=0;t<sigma.length;t++) reconstructed[t]+=push.residual()[v]*pv[t];
        }
        for(int t=0;t<sigma.length;t++) assertThat(reconstructed[t]).isCloseTo(exact[t],within(1e-11));
        for(long seed: new long[]{7,31,20261031}) {
            var config=new SimilaritySettings.Parameters(.15,.1,1e-6,10,100,32,2_000_000,5000,seed);
            var result=new Fora().compute(g,sigma,g.targets(1,sigma),3,config);
            assertThat(result.approximationTargetMet()).isTrue();
            assertThat(Arrays.stream(result.scores()).sum()).isCloseTo(1,within(1e-10));
            for(int t=0;t<sigma.length;t++) assertThat(result.scores()[t]).isCloseTo(exact[t],within(.005));
            assertThat(result.top()).allSatisfy(i -> {
                assertThat(g.nodes.get(i).type()).isEqualTo(SkillProjection.Type.CANDIDATE);
                assertThat(g.nodes.get(i).id()).isNotIn(1L,5L,6L);
            });
            assertThat(result.top()).contains(g.index.get(new SkillProjection.Node(SkillProjection.Type.CANDIDATE,3)));
            assertThat(result.delta()).isLessThan(1.0/g.nodes.size());
        }
    }
    @Test void deterministicResultAndWeightedSampling() {
        var g=new SkillProjection(fixture()); var sigma=g.sigma(1); var fora=new Fora();
        var a=fora.compute(g,sigma,g.targets(1,sigma),3,parameters());
        var b=fora.compute(g,sigma,g.targets(1,sigma),3,parameters());
        assertThat(a.scores()).containsExactly(b.scores()); assertThat(a.top()).isEqualTo(b.top());
        int v=g.index.get(new SkillProjection.Node(SkillProjection.Type.CANDIDATE,1));
        int skill=g.index.get(new SkillProjection.Node(SkillProjection.Type.SKILL,1));
        var random=new SplittableRandom(31); int hits=0;
        for(int i=0;i<100000;i++) if(Fora.next(g,v,random)==skill) hits++;
        assertThat(hits/100000.0).isCloseTo(.5,within(.01));
    }
    @Test void correctionUsesIndividualCeilWeightsAndPermitsZeroHopAndSelfLoops() {
        var g=new SkillProjection(new SimilaritySnapshot(List.of(new Profile(1,"A",""),new Profile(2,"B","")),List.of()));
        var push=new Fora.Push(new double[]{.1,.1},new double[]{.25,.55});
        var score=Fora.correct(g,push,1,3,new SplittableRandom(7),new Fora.Budget(parameters(),System::nanoTime));
        assertThat(score).containsExactly(.35,.65);
        var pushed=Fora.push(g,new double[]{1,0},.15,.001,new Fora.Budget(parameters(),System::nanoTime));
        assertThat(Arrays.stream(pushed.reserve()).sum()+Arrays.stream(pushed.residual()).sum()).isCloseTo(1,within(1e-12));
    }
    @Test void budgetDiscardsIncompleteRoundAndFailsWhenNoneCompleted() {
        var g=new SkillProjection(fixture()); var sigma=g.sigma(1);
        var limited=new SimilaritySettings.Parameters(.15,.1,1e-6,10,100,32,1,5000,31);
        assertThatThrownBy(()->new Fora().compute(g,sigma,g.targets(1,sigma),3,limited)).isInstanceOf(Fora.BudgetExceededException.class);
        var time=new AtomicLong();
        assertThatThrownBy(()->new Fora(()->time.getAndAdd(6_000_000_000L)).compute(g,sigma,g.targets(1,sigma),3,parameters()))
                .isInstanceOf(Fora.BudgetExceededException.class);
        var p=parameters();
        double e=p.epsilon()/2, delta=1.0/3;
        double b=(2+2*e/3)*Math.log(2/(p.failureProbability()/((double)g.nodes.size()*p.maxRounds())))/(e*e*delta);
        double threshold=(1/Math.sqrt(g.multiplicity))/Math.sqrt(b);
        if(g.multiplicity*threshold>1) threshold=1/b;
        var push=Fora.push(g,sigma,p.alpha(),threshold,new Fora.Budget(p,System::nanoTime));
        long firstWalks=Arrays.stream(push.residual()).filter(x->x>0).mapToLong(x->(long)Math.ceil(x*b)).sum();
        var capped=new SimilaritySettings.Parameters(.15,.1,1e-6,10,100,32,firstWalks,5000,p.seed());
        var result=new Fora().compute(g,sigma,g.targets(1,sigma),3,capped);
        var expected=Fora.correct(g,push,p.alpha(),b,new SplittableRandom(p.seed()).split(),new Fora.Budget(p,System::nanoTime));
        assertThat(result.rounds()).isEqualTo(1);
        assertThat(result.approximationTargetMet()).isFalse();
        assertThat(result.scores()).containsExactly(expected);
        assertThat(Arrays.stream(result.scores()).sum()).isCloseTo(1,within(1e-10));
    }
    @Test void unitWeightsHaveUniformTransitionsAndInterruptedWalkNeverCompletes() {
        var facts=new ArrayList<Evidence>(); add(facts,1,1,1); add(facts,1,2,1); add(facts,2,1,1);
        var g=new SkillProjection(new SimilaritySnapshot(List.of(new Profile(1,"A",""),new Profile(2,"B","")),facts));
        for(int v=0;v<g.nodes.size();v++) for(var a:g.adjacency.get(v))
            assertThat((double)a.weight()/g.degree[v]).isEqualTo(1.0/g.adjacency.get(v).size());
        var time=new AtomicLong();
        var budget=new Fora.Budget(parameters(),()->time.getAndAdd(1_000_000_000));
        var residual=g.sigma(1);
        assertThatThrownBy(()->Fora.correct(g,new Fora.Push(new double[residual.length],residual),
                Double.MIN_VALUE,100,new SplittableRandom(7),budget)).isInstanceOf(Fora.BudgetExceededException.class);
        assertThat(budget.walks).isZero();
    }
    @Test void validatesConfiguration() {
        var s=new SimilaritySettings(); s.setAlpha(Double.NaN);
        assertThatThrownBy(s::parameters).isInstanceOf(IllegalArgumentException.class);
    }
    static double[] oracle(SkillProjection g,double[] sigma,double alpha) {
        double[] pi=sigma.clone();
        for(int it=0;it<10000;it++) {
            double[] next=new double[pi.length];
            for(int i=0;i<pi.length;i++) next[i]=alpha*sigma[i];
            for(int v=0;v<pi.length;v++) for(var a:g.adjacency.get(v)) next[a.target()]+=(1-alpha)*pi[v]*a.weight()/g.degree[v];
            double error=0; for(int i=0;i<pi.length;i++) error+=Math.abs(next[i]-pi[i]);
            pi=next; if(error<1e-14) return pi;
        }
        throw new AssertionError("Oracle did not converge");
    }
}
