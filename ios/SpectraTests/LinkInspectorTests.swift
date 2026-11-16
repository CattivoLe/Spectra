import Foundation
import Testing
@testable import Spectra

struct LinkInspectorTests {
    private func check(_ text: String) throws -> LinkInspection {
        try #require(LinkInspector.inspect(text))
    }

    private func kinds(_ inspection: LinkInspection) -> Set<LinkInspection.Finding.Kind> {
        Set(inspection.findings.map(\.kind))
    }

    @Test func officialSiteRaisesNoFlags() throws {
        let inspection = try check("https://www.apple.com/iphone/")
        #expect(inspection.site == "apple.com")
        #expect(inspection.verdict == .noFlags)
        #expect(kinds(inspection) == [.official])
    }

    @Test func brandInFrontOfAnotherSiteIsPhishing() throws {
        let inspection = try check("https://appleid.apple.com.verify-login.xyz/signin")
        #expect(inspection.site == "verify-login.xyz")
        #expect(inspection.verdict == .dangerous)
        #expect(kinds(inspection).isSuperset(of: [.brandInSubdomain, .riskyZone, .baitWords]))
    }

    @Test func digitForALetterIsALookalike() throws {
        let inspection = try check("http://paypa1.com/login")
        #expect(inspection.site == "paypa1.com")
        #expect(inspection.verdict == .dangerous)
        #expect(kinds(inspection).isSuperset(of: [.lookalike, .insecure]))
    }

    @Test func cyrillicHomographIsCaught() throws {
        let inspection = try check("https://xn--80ak6aa92e.com/")
        #expect(inspection.host == "аррӏе.com")
        #expect(inspection.asciiHost == "xn--80ak6aa92e.com")
        #expect(inspection.verdict == .dangerous)
        #expect(kinds(inspection).isSuperset(of: [.lookalike, .lookalikeLetters, .punycode]))
    }

    @Test func oneCyrillicLetterAmongLatinIsCaught() throws {
        let inspection = try check("https://p\u{0430}ypal.com/")
        #expect(kinds(inspection).contains(.mixedScripts))
        #expect(inspection.verdict == .dangerous)
    }

    @Test func cyrillicSiteInACyrillicZoneIsFine() throws {
        let inspection = try check("https://xn--d1acufc.xn--p1ai/")
        #expect(inspection.host == "домен.рф")
        #expect(inspection.verdict == .noFlags)
        #expect(kinds(inspection).contains(.foreignScript))
    }

    @Test func userInfoHidesTheRealHost() throws {
        let inspection = try check("https://www.google.com@evil.example/x")
        #expect(inspection.site == "evil.example")
        #expect(kinds(inspection).contains(.userInfo))
        #expect(inspection.verdict == .dangerous)
    }

    @Test func ipAddressInsteadOfAName() throws {
        let inspection = try check("http://203.0.113.7/bank")
        #expect(inspection.site == "203.0.113.7")
        #expect(kinds(inspection).contains(.ipAddress))
        #expect(inspection.verdict == .dangerous)
        #expect(try kinds(check("http://3232235777/")).contains(.ipAddress))
    }

    @Test func shortLinkAsksForACloserLook() throws {
        let inspection = try check("https://bit.ly/3AbCdE")
        #expect(kinds(inspection) == [.shortener])
        #expect(inspection.verdict == .caution)
    }

    @Test func linkIsFoundInsideAMessage() throws {
        let inspection = try check("Здравствуйте! Ваш возврат оформлен: https://gosuslugi-vozvrat.ru/form. Спасибо")
        #expect(inspection.link == "https://gosuslugi-vozvrat.ru/form")
        #expect(inspection.site == "gosuslugi-vozvrat.ru")
        #expect(kinds(inspection).isSuperset(of: [.brandInDomain, .baitWords]))
        #expect(inspection.verdict == .suspicious)
    }

    @Test func mailFilterWrapperIsOpened() throws {
        let inspection = try check("https://nam12.safelinks.protection.outlook.com/?url=https%3A%2F%2Fpaypa1.com%2Flogin&data=05")
        #expect(inspection.ownVerdict == .noFlags)
        #expect(inspection.nested.first?.site == "paypa1.com")
        #expect(kinds(inspection).contains(.leadsOn))
        #expect(inspection.verdict == .dangerous)
    }

    @Test func freeHostingAccountIsTheSite() throws {
        let inspection = try check("https://apple-support.github.io/")
        #expect(inspection.site == "apple-support.github.io")
        #expect(kinds(inspection).isSuperset(of: [.freeHosting, .brandInDomain]))
        #expect(inspection.verdict == .suspicious)
    }

    @Test func twoLabelSuffixKeepsTheOwner() throws {
        let inspection = try check("https://www.bbc.co.uk/news")
        #expect(inspection.site == "bbc.co.uk")
        #expect(inspection.verdict == .noFlags)
    }

    @Test func bareAddressWithoutScheme() throws {
        let inspection = try check("apple.com.ru-login.top")
        #expect(inspection.scheme == "https")
        #expect(inspection.site == "ru-login.top")
        #expect(kinds(inspection).contains(.brandInSubdomain))
    }

    @Test func typosquatIsCaughtButSimilarWordsAreNot() throws {
        #expect(try kinds(check("https://micosoft.com")).contains(.lookalike))
        #expect(try !kinds(check("https://finance.com")).contains(.lookalike))
    }

    @Test func scriptLinksAreDangerous() throws {
        let inspection = try check("javascript:alert(1)")
        #expect(kinds(inspection) == [.notWeb])
        #expect(inspection.verdict == .dangerous)
        #expect(inspection.url == nil)
        #expect(try check("mailto:someone@example.com").verdict == .noFlags)
    }

    @Test func unusualPortIsNoted() throws {
        #expect(try kinds(check("https://example.com:8443/")).contains(.unusualPort))
    }

    @Test func textWithoutALinkIsNotInspected() {
        #expect(LinkInspector.inspect("привет, как дела") == nil)
        #expect(LinkInspector.inspect("   ") == nil)
    }

    @Test func punycodeDecodes() {
        #expect(LinkInspector.punycodeDecode("80ak6aa92e") == "аррӏе")
        #expect(LinkInspector.punycodeDecode("d1acufc") == "домен")
        #expect(LinkInspector.punycodeDecode("!!") == nil)
    }
}
