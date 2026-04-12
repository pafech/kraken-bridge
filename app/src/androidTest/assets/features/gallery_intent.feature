@gallery-intent
Feature: Gallery opens latest media in single-item view
  As a diver reviewing photos during a safety stop
  I want the gallery button to open my latest photo or video directly
  So that I can immediately review and swipe through my shots

  Background:
    Given a test image has been seeded into MediaStore

  @smoke
  Scenario: Latest media is found when full photo access is granted
    When the latest media is queried
    Then a content URI is returned
    And the MIME type is "image/*"

  @smoke
  Scenario: Gallery intent includes MIME type for single-item view
    When the gallery intent is constructed for the latest media
    Then the intent action is ACTION_VIEW
    And the intent has a data URI set
    And the intent MIME type is not null

  Scenario: Gallery intent targets Google Photos when installed
    When the gallery intent is constructed for the latest media
    Then the intent package is "com.google.android.apps.photos"

  Scenario: Video media returns video MIME type
    Given a test video entry has been seeded into MediaStore
    When the latest media is queried
    Then the MIME type is "video/*"
