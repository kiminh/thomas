package com.iheart.thomas
package bandit
package bayesian
import java.time.{OffsetDateTime, ZoneOffset}

import cats.effect.Sync
import cats.Monoid
import cats.implicits._
import com.iheart.thomas.abtest.model.{AbtestSpec, Group}
import com.iheart.thomas.analysis._
import com.stripe.rainier.sampler.RNG
import henkan.convert.Syntax._

object ConversionBMABAlg {

  def default[F[_]](stateDao: BanditStateDAO[F, BanditState[Conversions]],
                    kpiAPI: KPIApi[F],
                    abtestAPI: abtest.AbtestAlg[F])(implicit
                                                    sampleSettings: SampleSettings,
                                                    rng: RNG,
                                                    F: Sync[F]): ConversionBMABAlg[F] =
    new ConversionBMABAlg[F] {

      def updateRewardState(
          featureName: FeatureName,
          rewards: Map[ArmName, Conversions]): F[BanditState[Conversions]] = {
        implicit val mc: Monoid[Conversions] = RewardState[Conversions]
        for {
          cs <- currentState(featureName)
          toUpdate = cs.state.updateArms(rewards)
          _ <- stateDao.upsert(toUpdate)
        } yield toUpdate
      }

      def init(banditSpec: BanditSpec): F[BayesianMAB[Conversions]] = {
        (abtestAPI
           .create(
             AbtestSpec(
               name = "Abtest for Bayesian MAB " + banditSpec.feature,
               feature = banditSpec.feature,
               author = banditSpec.author,
               start = banditSpec.start.atOffset(ZoneOffset.UTC),
               end = None,
               groups = banditSpec.arms.map(
                 Group(_, 1d / banditSpec.arms.size.toDouble)
               )
             ),
             false
           ),
         stateDao
           .upsert(
             BanditState[Conversions](
               feature = banditSpec.feature,
               title = banditSpec.title,
               author = banditSpec.author,
               arms = banditSpec.arms.map(
                 ArmState(_, RewardState[Conversions].empty, Probability(0d))),
               start = banditSpec.start
             )
           )).mapN(BayesianMAB.apply _)
      }

      def reallocate(featureName: FeatureName,
                     kpiName: KPIName): F[BayesianMAB[Conversions]] = {
        for {
          current <- currentState(featureName)
          kpi <- kpiAPI.getSpecific[BetaKPIDistribution](kpiName)
          BayesianMAB(abtest, state) = current
          possibilities <- BetaKPIDistribution.basicAssessmentAlg
            .assessOptimumGroup(kpi, state.rewardState)
          abtest <- abtestAPI.continue(
            abtest.data
              .to[AbtestSpec]
              .set(start = OffsetDateTime.now, groups = abtest.data.groups.map { g =>
                g.copy(size = possibilities.get(g.name).fold(g.size)(identity))
              }))
        } yield BayesianMAB(abtest, state)

      }

      def currentState(featureName: FeatureName): F[BayesianMAB[Conversions]] = {
        (abtestAPI
           .getTestsByFeature(featureName)
           .flatMap(_.headOption.liftTo[F](AbtestNotFound(featureName))),
         stateDao.get(featureName)).mapN(BayesianMAB.apply _)
      }
    }
}