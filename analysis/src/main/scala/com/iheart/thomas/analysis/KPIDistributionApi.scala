package com.iheart
package thomas
package analysis

import lihua.{Entity, EntityDAO}
import _root_.play.api.libs.json.JsObject
import cats.implicits._
import abtest.Error
import cats.tagless.autoFunctorK
import com.iheart.thomas.abtest.Error.NotFound

import scala.reflect.ClassTag

@autoFunctorK
trait KPIDistributionApi[F[_]] {
  def get(name: KPIName): F[Option[Entity[KPIDistribution]]]

  def getAll: F[Vector[Entity[KPIDistribution]]]

  def getSpecific[K <: KPIDistribution](
      name: KPIName
    )(implicit classTag: ClassTag[K]
    ): F[K]

  def upsert(kpi: KPIDistribution): F[Entity[KPIDistribution]]
}

object KPIDistributionApi {

  implicit def default[F[_]](
      implicit dao: EntityDAO[F, KPIDistribution, JsObject],
      F: MonadThrowable[F]
    ): KPIDistributionApi[F] = new KPIDistributionApi[F] {
    import com.iheart.thomas.abtest.QueryDSL._
    def get(name: KPIName): F[Option[Entity[KPIDistribution]]] =
      dao.findOneOption('name -> name)

    def getAll: F[Vector[Entity[KPIDistribution]]] =
      dao.all

    def getSpecific[K <: KPIDistribution](
        name: KPIName
      )(implicit classTag: ClassTag[K]
      ): F[K] =
      get(name).flatMap {
        case Some(Entity(id, k: K)) => F.pure(k)
        case _ =>
          F.raiseError(
            NotFound(s"Cannot find the KPI of name $name and ${classTag}")
          )
      }

    def upsert(kpi: KPIDistribution): F[Entity[KPIDistribution]] =
      dao
        .findOne('name -> kpi.name.n)
        .flatMap(e => dao.update(e.copy(data = kpi)))
        .recoverWith { case Error.NotFound(_) => dao.insert(kpi) }
  }
}
