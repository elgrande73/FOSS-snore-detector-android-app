package com.aistudio.snoredetector.afkwd.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.snoredetector.afkwd.BuildConfig
import kotlinx.coroutines.launch

@Composable
fun GuideTab() {
    var selectedFilterIndex by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val categories = listOf(
        "All Sections",
        "1-3 Setup & Metrics",
        "4 Troubleshooting",
        "5-6 Lifestyle & Habits",
        "7-8 Medical & Scope",
        "9 Privacy & FOSS"
    )

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("guide_tab"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "guide_hero_header") {
            Spacer(modifier = Modifier.height(8.dp))
            // Hero Title Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "How to use Snore Detector",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "User Guide & Information Reference",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Snore Detector listens to the sound around your bed and detects acoustic events that resemble snoring. It processes the audio locally on your phone and records detected events, their timing, duration and acoustic characteristics.",
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "The app is intended for personal self-monitoring, not for diagnosing medical conditions.",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // Quick Category Filter Row
        item(key = "guide_category_chips") {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(categories.size, key = { categories[it] }) { index ->
                    FilterChip(
                        selected = selectedFilterIndex == index,
                        onClick = {
                            selectedFilterIndex = index
                            coroutineScope.launch {
                                listState.animateScrollToItem(0)
                            }
                        },
                        label = { Text(categories[index], fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    )
                }
            }
        }

            // SECTION 1: Getting Started
        if (selectedFilterIndex == 0 || selectedFilterIndex == 1) {
            item(key = "guide_section_1") {
                GuideSectionCard(
                    sectionNumber = "1",
                    title = "Getting started",
                    defaultExpanded = false
                ) {
                    Text(
                        text = "For the best results:",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    BulletPoint("Place your phone near the bed, with the microphone unobstructed and pointing roughly toward your head.")
                    BulletPoint("Keep the phone in a similar position on different nights.")
                    BulletPoint("Connect it to power if necessary.")
                    BulletPoint("Minimize background noise where practical.")
                    BulletPoint("Start detection before going to sleep.")
                    
                    Spacer(modifier = Modifier.height(10.dp))
                    HighlightBox(
                        text = "You do not need to place the phone directly under your pillow. Covering the microphone can interfere with recording.\n\nFor meaningful comparisons, use the same phone and approximately the same setup whenever possible."
                    )
                }
            }

            // SECTION 2: Record a night
            item(key = "guide_section_2") {
                GuideSectionCard(
                    sectionNumber = "2",
                    title = "Record a night",
                    defaultExpanded = false
                ) {
                    Text(
                        text = "Let Snore Detector run overnight without moving the phone.\n\nIn the morning, review:",
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    BulletPoint("Detected snoring events")
                    BulletPoint("Time and duration of events")
                    BulletPoint("Peak acoustic level (dB)")
                    BulletPoint("Low-frequency ratio & frequency energy")
                    BulletPoint("Optional audio clips")

                    Spacer(modifier = Modifier.height(10.dp))
                    HighlightBox(
                        text = "A single night can be misleading. For personal tracking, it is more useful to compare several nights and look for recurring patterns.\n\nYou may also note factors such as sleep position, alcohol consumption, congestion, meal timing or unusual fatigue. This can help you identify personal patterns."
                    )
                }
            }

            // SECTION 3: Understanding the results
            item(key = "guide_section_3") {
                GuideSectionCard(
                    sectionNumber = "3",
                    title = "Understanding the results",
                    defaultExpanded = false
                ) {
                    Text(
                        text = "Snore Detector identifies sounds that match characteristics of snoring. Its measurements are acoustic measurements, not clinical measurements of sleep or breathing.",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    MetricExplanationItem(
                        title = "Acoustic level (Sound Volume in Decibels)",
                        description = "Shows how strong the recorded audio signal was in decibels (dB) calculated from RMS power. Unless specifically calibrated, this should not be interpreted as a laboratory-accurate sound-pressure measurement."
                    )
                    MetricExplanationItem(
                        title = "Zero-Crossing Rate (ZCR / Pitch)",
                        description = "Describes how often the audio waveform crosses zero. Snoring is a low-pitched rumble with few zero-crossings, distinguishing it from higher-pitched sounds."
                    )
                    MetricExplanationItem(
                        title = "Snoring Frequency Band Energy",
                        description = "Measures acoustic energy concentrated in the 100 Hz – 1,000 Hz human snoring frequency band, filtering out flat background noise."
                    )
                    MetricExplanationItem(
                        title = "Low-Frequency Energy Ratio",
                        description = "Compares energy below 500 Hz with total spectral energy. Deep airway snoring vibrations are concentrated in this low-frequency zone."
                    )
                    MetricExplanationItem(
                        title = "Event Duration",
                        description = "The duration in seconds during which the sound continuously satisfied all active acoustic criteria."
                    )
                }
            }
        }

        // SECTION 4: No snoring detected?
        if (selectedFilterIndex == 0 || selectedFilterIndex == 2) {
            item(key = "guide_section_4") {
                GuideSectionCard(
                    sectionNumber = "4",
                    title = "No snoring detected?",
                    defaultExpanded = false
                ) {
                    Text(
                        text = "\"No detection\" does not necessarily mean that you did not snore.\n\nPossible reasons include:",
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    BulletPoint("You did not snore that night")
                    BulletPoint("The phone was too far away")
                    BulletPoint("The microphone was obstructed")
                    BulletPoint("Background noise affected detection")
                    BulletPoint("The detection threshold was too high")
                    BulletPoint("The selected algorithms were not suitable for the recording")
                    BulletPoint("Your phone's microphone or audio processing differs from other devices")

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Before changing parameters, check the phone placement and microphone.\n\nIf detection appears too insensitive, try:",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    BulletPoint("Move the phone somewhat closer.")
                    BulletPoint("Make sure the microphone is unobstructed.")
                    BulletPoint("Adjust the relevant sensitivity/threshold gradually.")
                    BulletPoint("Change one parameter at a time.")
                    BulletPoint("Compare several nights.")

                    Spacer(modifier = Modifier.height(8.dp))
                    HighlightBox(
                        text = "⚠️ Lowering a threshold can increase sensitivity, but it can also increase false detections."
                    )
                }
            }
        }

        // SECTION 5: What can help with snoring?
        if (selectedFilterIndex == 0 || selectedFilterIndex == 3) {
            item(key = "guide_section_5") {
                GuideSectionCard(
                    sectionNumber = "5",
                    title = "What can help with snoring?",
                    defaultExpanded = false,
                    badgeText = "Evidence-Based Lifestyle Factors",
                    badgeColor = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "Some factors are associated with snoring and sleep-disordered breathing. Depending on the individual situation, you may consider:",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    MetricExplanationItem(
                        title = "Sleeping position",
                        description = "If you notice more snoring while sleeping on your back, try sleeping on your side or using an appropriate positional strategy. Positional therapy and head-of-bed elevation have some evidence for reducing snoring in certain people, although the effect varies between individuals."
                    )
                    MetricExplanationItem(
                        title = "Alcohol",
                        description = "Alcohol close to bedtime can worsen snoring and sleep-disordered breathing in some people. Avoiding alcohol near bedtime may therefore be worth trying."
                    )
                    MetricExplanationItem(
                        title = "Healthy weight",
                        description = "If you are overweight, healthy and sustainable weight management may improve sleep-disordered breathing. This does not mean that everyone who snores needs to lose weight."
                    )
                    MetricExplanationItem(
                        title = "Physical activity",
                        description = "Regular physical activity is beneficial for general health and is also part of lifestyle approaches used in managing sleep-disordered breathing."
                    )
                    MetricExplanationItem(
                        title = "Nasal congestion",
                        description = "A blocked nose can contribute to snoring in some people. If nasal congestion is persistent, consider discussing the cause and appropriate treatment with a healthcare professional."
                    )
                }
            }

            // SECTION 6: Commonly suggested lifestyle ideas — evidence is limited or unclear
            item(key = "guide_section_6") {
                GuideSectionCard(
                    sectionNumber = "6",
                    title = "Commonly suggested lifestyle ideas — evidence is limited or unclear",
                    defaultExpanded = false,
                    badgeText = "🟡 Evidence: Limited / Unclear",
                    badgeColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
                ) {
                    Text(
                        text = "You may encounter many additional \"anti-snoring hacks\" online.\n\nSome are harmless lifestyle experiments, but there is not enough evidence to say that they reliably reduce snoring or prevent sleep apnea.\n\n🟡 The following are included as ideas people commonly try, not as medical recommendations.",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    MetricExplanationItem(
                        title = "Intermittent fasting",
                        description = "Sometimes suggested because of its potential effects on weight and metabolic health. There is currently not enough evidence to recommend intermittent fasting specifically as a treatment for snoring."
                    )
                    MetricExplanationItem(
                        title = "Not eating shortly before sleep",
                        description = "You may hear advice such as \"don't eat for three hours before bed.\" Avoiding large or late meals may work well for some people's sleep habits, but a universal 3-hour rule has not been established as a treatment for snoring. If you notice that late meals consistently coincide with more snoring, you can experiment with eating earlier."
                    )
                    MetricExplanationItem(
                        title = "Low-carbohydrate diets",
                        description = "Low-carb diets are sometimes promoted online for snoring and sleep apnea. There is not enough evidence to recommend a low-carbohydrate diet specifically as a treatment for snoring. Different healthy dietary approaches may be appropriate for different people."
                    )
                    MetricExplanationItem(
                        title = "\"Anti-inflammatory\" diets",
                        description = "Healthy dietary patterns can be beneficial for general health, but there is insufficient evidence to recommend a particular \"anti-inflammatory diet\" as a treatment for snoring."
                    )
                    MetricExplanationItem(
                        title = "Drinking more water",
                        description = "Adequate hydration is important for general health, but simply drinking more water has not been established as a reliable treatment for snoring. Drink according to your normal hydration needs rather than deliberately drinking large amounts immediately before bed."
                    )
                    MetricExplanationItem(
                        title = "Natural materials in the bedroom",
                        description = "You may encounter claims that replacing synthetic furniture, curtains, mattresses or bedding with \"natural\" materials reduces snoring. There is no established evidence that natural materials themselves reduce snoring. If you have a known allergy, sensitivity, dust exposure or mold problem, addressing that specific environmental issue may nevertheless be worthwhile."
                    )
                    MetricExplanationItem(
                        title = "Air purifiers and bedroom air",
                        description = "A comfortable, clean and well-ventilated bedroom can be beneficial for general sleep comfort. However, there is insufficient evidence to claim that an air purifier or a particular type of indoor-air treatment reliably reduces snoring."
                    )
                    MetricExplanationItem(
                        title = "Stretching and throat exercises",
                        description = "Various tongue, throat, jaw, neck and breathing exercises are promoted online. Some specific exercise-based approaches have been studied, but evidence is not strong enough to present generic exercises as a reliable treatment for snoring."
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    HighlightBox(
                        text = "Try one change at a time:\n• Change one thing at a time\n• Keep your phone in approximately the same position\n• Compare several nights\n• Avoid conclusions based on a single night\n\nA reduction in detected snoring is an observation, not proof that the intervention is medically effective. And \"unproven\" does not necessarily mean \"harmful\" — it means that reliable evidence is currently insufficient."
                    )
                }
            }
        }

        // SECTION 7: When should I talk to a doctor?
        if (selectedFilterIndex == 0 || selectedFilterIndex == 4) {
            item(key = "guide_section_7") {
                GuideSectionCard(
                    sectionNumber = "7",
                    title = "When should I talk to a doctor?",
                    defaultExpanded = false,
                    badgeText = "Important Medical Guidance",
                    badgeColor = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = "Loudness alone does not determine whether snoring is medically significant.\n\nSome people with relatively quiet snoring have sleep apnea, while others who snore loudly do not.",
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Consider discussing your symptoms with a healthcare professional if snoring is accompanied by:",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    BulletPoint("Witnessed pauses in breathing")
                    BulletPoint("Choking or gasping during sleep")
                    BulletPoint("Excessive daytime sleepiness")
                    BulletPoint("Morning headaches")
                    BulletPoint("High blood pressure")
                    BulletPoint("Persistent or severe snoring")
                    BulletPoint("Unexplained fatigue or concentration problems")

                    Spacer(modifier = Modifier.height(12.dp))
                    HighlightBox(
                        text = "A healthcare professional may recommend an appropriate sleep evaluation.\n\nImportantly, an improvement in your Snore Detector recordings does not rule out sleep apnea, and continued snoring does not prove that you have it.\n\nIf obstructive sleep apnea is diagnosed, treatment should be discussed with a healthcare professional. Depending on the individual situation, treatment may include positive airway pressure (PAP/CPAP), among other options."
                    )
                }
            }

            // SECTION 8: What Snore Detector can and cannot tell you
            item(key = "guide_section_8") {
                GuideSectionCard(
                    sectionNumber = "8",
                    title = "What Snore Detector can and cannot tell you",
                    defaultExpanded = false
                ) {
                    Text(
                        text = "The app analyzes sound, not your complete sleep physiology.",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Snore Detector can help you:",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            BulletPoint("Detect sounds resembling snoring")
                            BulletPoint("Observe patterns across multiple nights")
                            BulletPoint("Identify possible environmental influences")
                            BulletPoint("Compare recordings before and after personal changes")
                            BulletPoint("Listen to selected audio clips")
                            BulletPoint("Keep a local history")
                            BulletPoint("Export your data for personal analysis or discussion with a healthcare professional")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Snore Detector cannot determine:",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            BulletPoint("Whether you have sleep apnea")
                            BulletPoint("Your apnea-hypopnea index (AHI)")
                            BulletPoint("Your blood oxygen level")
                            BulletPoint("Whether breathing actually stopped")
                            BulletPoint("Your sleep stages")
                            BulletPoint("Respiratory effort")
                            BulletPoint("The medical severity of snoring")
                            BulletPoint("Which treatment is appropriate for you")
                        }
                    }
                }
            }
        }

        // SECTION 9: Privacy & FOSS
        if (selectedFilterIndex == 0 || selectedFilterIndex == 5) {
            item(key = "guide_section_9") {
                GuideSectionCard(
                    sectionNumber = "9",
                    title = "Privacy & FOSS",
                    defaultExpanded = false,
                    badgeText = "100% On-Device & FOSS",
                    badgeColor = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "Snore Detector is designed around local, user-controlled data.\n\nAudio processing and event detection take place on your device. Your recordings and detection history do not need to be uploaded to a server.\n\nYou can review your history locally and export your detection data when you choose.\n\nThe application is free and open source, allowing users to inspect and contribute to the software.\n\nLike other consumer self-monitoring tools, Snore Detector provides information rather than a medical diagnosis.",
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Snore Detector",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Version ${BuildConfig.VERSION_NAME}",
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        // Bottom Disclaimer Box
        item(key = "guide_disclaimer") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Medical Disclaimer",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = "Important Medical Disclaimer",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Snore Detector is a self-monitoring and information tool. It is not a medical diagnostic device and cannot diagnose or rule out sleep apnea or other medical conditions.\n\nIf you are concerned about your breathing during sleep or have symptoms such as pauses in breathing, gasping/choking, excessive daytime sleepiness, morning headaches or high blood pressure, please discuss them with a qualified healthcare professional.",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun GuideSectionCard(
    sectionNumber: String,
    title: String,
    defaultExpanded: Boolean = false,
    badgeText: String? = null,
    badgeColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(defaultExpanded) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("guide_section_$sectionNumber")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = sectionNumber,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Column {
                        Text(
                            text = title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (badgeText != null) {
                            Surface(
                                color = badgeColor,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Text(
                                    text = badgeText,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 14.dp)) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        thickness = 1.dp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    content()
                }
            }
        }
    }
}

@Composable
fun BulletPoint(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "•",
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = text,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun HighlightBox(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
fun MetricExplanationItem(title: String, description: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = title,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = description,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

