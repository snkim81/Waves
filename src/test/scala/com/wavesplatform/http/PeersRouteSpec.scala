package com.wavesplatform.http

import java.net.{InetAddress, InetSocketAddress}
import java.util.concurrent.ConcurrentMap

import com.wavesplatform.http.ApiMarshallers._
import com.wavesplatform.network.{PeerDatabase, PeerInfo}
import io.netty.channel.Channel
import org.scalacheck.{Arbitrary, Gen}
import org.scalamock.scalatest.MockFactory
import org.scalatest.prop.PropertyChecks
import play.api.libs.json.{Format, JsObject, JsValue, Json}
import scorex.api.http.{ApiKeyNotValid, PeersApiRoute}

import scala.collection.JavaConverters._

class PeersRouteSpec extends RouteSpec("/peers") with RestAPISettingsHelper with PropertyChecks with MockFactory {
  import PeersRouteSpec._

  private val peerDatabase = mock[PeerDatabase]
  private val connections = mock[ConcurrentMap[Channel, PeerInfo]]
  private val connectToPeer = mockFunction[InetSocketAddress, Unit]
  private val route = PeersApiRoute(restAPISettings, connectToPeer, peerDatabase, connections).route

  private val inetAddressGen = Gen.listOfN(4, Arbitrary.arbitrary[Byte]).map(_.toArray).map(InetAddress.getByAddress)
  private val inetSocketAddressGen = for {
    address <- inetAddressGen
    port <- Gen.chooseNum(0, 0xFFFF)
  } yield new InetSocketAddress(address, port)

  private val versionGen = for {
    major <- Gen.chooseNum(0, 3)
    minor <- Gen.chooseNum(0, 3)
    patch <- Gen.chooseNum(0, 3)
  } yield (major, minor, patch)

  private def genListOf[A](maxLength: Int, src: Gen[A]) = Gen.chooseNum(0, maxLength).flatMap(n => Gen.listOfN(n, src))

  routePath("/connected") in {
    val gen = for {
      remoteAddress <- inetSocketAddressGen
      declaredAddress <- Gen.option(inetSocketAddressGen)
      nodeName <- Gen.alphaNumStr
      nodeNonce <- Arbitrary.arbitrary[Int]
      applicationName <- Gen.alphaNumStr
      applicationVersion <- versionGen
    } yield PeerInfo(remoteAddress.toString, declaredAddress, applicationName, applicationVersion, nodeName, nodeNonce)

    forAll(genListOf(TestsCount, gen)) { l: List[PeerInfo] =>

      (connections.values _).expects().returning(l.asJava)

      val result = Get(routePath("/connected")) ~> route ~> runRoute

      check {
        responseAs[Connected].peers should contain theSameElementsAs l.map { pi =>
          ConnectedPeer(pi.remoteAddress, pi.declaredAddress.fold("N/A")(_.toString),
            pi.nodeName, pi.nodeNonce, pi.applicationName,
            s"${pi.applicationVersion._1}.${pi.applicationVersion._2}.${pi.applicationVersion._3}")
        }
      }(result)
    }
  }

  routePath("/all") in {
    val gen = for {
      inetAddress <- inetSocketAddressGen
      ts <- Gen.posNum[Long]
    } yield inetAddress -> ts


    forAll(genListOf(TestsCount, gen)) { m =>
      (peerDatabase.knownPeers _).expects().returning(m.toMap[InetSocketAddress, Long])

      val result = Get(routePath("/all")) ~> route ~> runRoute

      check {
        responseAs[AllPeers].peers should contain theSameElementsAs m.map {
          case (address, timestamp) => Peer(address.toString, timestamp)
        }
      }(result)
    }
  }

  routePath("/connect") in {
    val connectUri = routePath("/connect")
    Post(connectUri, ConnectReq("example.com", 1)) ~> route should produce (ApiKeyNotValid)
    Post(connectUri, "") ~> api_key(apiKey) ~> route ~> check(handled shouldEqual false)
    Post(connectUri, Json.obj()) ~> api_key(apiKey) ~> route ~> check {
      (responseAs[JsValue] \ "validationErrors").as[JsObject].keys should not be 'empty
    }

    forAll(inetSocketAddressGen) { address =>
      connectToPeer.expects(address).once

      val result = Post(connectUri, ConnectReq(address.getHostName, address.getPort)) ~> api_key(apiKey) ~> route ~> runRoute

      check {
        responseAs[ConnectResp].hostname shouldEqual address.getHostName
      }(result)
    }
  }

  routePath("/blacklisted") ignore {
    forAll(genListOf(TestsCount, inetSocketAddressGen)) { addresses =>
      val addressSet = addresses.map(_.getAddress).toSet

      (peerDatabase.blacklistedHosts _).expects().returning(addressSet)

      val result = Get(routePath("/blacklisted")) ~> route ~> runRoute

      check {
        responseAs[Seq[BlacklistedPeer]].map(_.hostname) should contain theSameElementsAs addressSet.map(_.toString)
      }(result)
    }
  }
}

object PeersRouteSpec {
  val TestsCount = 20

  case class ConnectReq(host: String, port: Int)
  implicit val connectReqFormat: Format[ConnectReq] = Json.format

  case class ConnectResp(status: String, hostname: String)
  implicit val connectRespFormat: Format[ConnectResp] = Json.format

  case class ConnectedPeer(address: String, declaredAddress: String, peerName: String, peerNonce: Long,
                           applicationName: String, applicationVersion: String)
  implicit val connectedPeerFormat: Format[ConnectedPeer] = Json.format

  case class Connected(peers: Seq[ConnectedPeer])
  implicit val connectedFormat: Format[Connected] = Json.format

  case class Peer(address: String, lastSeen: Long)
  implicit val peerFormat: Format[Peer] = Json.format

  case class BlacklistedPeer(hostname : String, timestamp: Long, reason: String)
  implicit val blacklistedPeerFormat: Format[BlacklistedPeer] = Json.format

  case class AllPeers(peers: Seq[Peer])
  implicit val allPeersFormat: Format[AllPeers] = Json.format
}
